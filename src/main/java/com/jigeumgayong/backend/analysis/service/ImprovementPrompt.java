package com.jigeumgayong.backend.analysis.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.TimeText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 입력(facts)·지시문·출력 스키마·prompt_version.
 * 지시문, 스키마, facts 구성, 검증 규칙 중 하나라도 바꾸면 PROMPT_VERSION을 올린다
 * (그러면 IMPROVEMENT 캐시만 다음 조회 때 재생성된다). 모델 변경은 올리지 않는다.
 */
final class ImprovementPrompt {

    static final String PROMPT_VERSION = "imp-v2";
    static final String SCHEMA_NAME = "improvement_proposal";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String[] DAY_LABELS = { "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일" };

    static final String INSTRUCTIONS = """
            당신은 용인시 공공시설의 운영시간 개선안을 검토하는 보조자입니다.
            사용자가 보낸 JSON 사실만 근거로, 희망 이용 시간에 시설 공간이 닫혀 있는 문제의 개선안을 제안합니다.
            규칙:
            1. 입력에 없는 사실을 만들지 않습니다. 인원, 비용, 예산, 효과(%), 이용자 수, 배차 간격 등 입력에 없는 숫자는 절대 쓰지 않습니다.
               정류장 수는 입력에 busStopCount가 있을 때만 "시설 500m 내 정류장 N개"로 그 값 그대로 쓰고, 입력에 없으면 정류장을 언급하지 않습니다.
            2. 시각은 입력에 있는 시각이나 proposedClosingTime의 시각만 "HH:mm" 형식으로 씁니다.
            3. 확정된 정책이 아니라 검토할 수 있는 제안으로 표현합니다. (예: "~를 검토할 수 있습니다")
            4. extendable이 false이면 proposedClosingTime은 null로 두고, 조기 개방이나 휴관일 시범 운영 같은 다른 운영 대안을 제안합니다.
            5. extendable이 true이면 proposedClosingTime은 currentClosingTime보다 늦고 requestEnd 이상인 "HH:mm" 시각이며, requestEnd와 같게 두는 것을 우선합니다.
            6. requiresReservation이 true이면 integratedSuggestion에 "예약 필요"를 반드시 포함합니다.
            7. 교통 개선 효과를 단정하지 않습니다. 정류장 수는 공간 정보일 뿐 운행 여부를 뜻하지 않습니다.
               배차, 막차, 첫차, 운행 간격, 도착 시간, 귀가 가능 여부는 만들거나 추정하지 않고, 필요하면 "실제 운행은 별도 확인이 필요합니다"라고만 안내합니다.
            8. 한국어 존댓말로 씁니다. alternatives는 1~3개, 각 120자 이내. integratedSuggestion은 1~2문장, 200자 이내.
            JSON 외의 텍스트는 출력하지 않습니다.
            """;

    /**
     * LLM에 넘기는 사실. 필드 순서가 곧 JSON 순서이므로 source_hash가 안정적이다.
     * busStopCount·busStopRadiusMeters는 교통이 AVAILABLE일 때만 들어가고(NO_DATA·NOT_ENABLED는 필드 생략),
     * 값이 바뀌면 source_hash가 달라져 캐시가 재생성된다.
     */
    record Facts(String facilityName, String facilityType, String accessType, boolean requiresReservation,
                 String spaceLabel, String dayOfWeek, String requestStart, String requestEnd, String dayState,
                 List<TimeInterval> openIntervals, String currentClosingTime,
                 List<Gap> unavailableIntervals, boolean extendable,
                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer busStopCount,
                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer busStopRadiusMeters) {
    }

    private ImprovementPrompt() {
    }

    static Facts facts(Candidate c, Accessibility transport) {
        boolean available = transport.status() == Status.AVAILABLE;
        return new Facts(c.facility().getName(), c.facility().getType().getName(),
                c.facility().getAccessType().name(), c.requiresReservation(), c.spaceLabel(),
                DAY_LABELS[c.dayOfWeek()], TimeText.format(c.startMin()), TimeText.format(c.endMin()),
                c.selectedSchedule().state(), c.selectedSchedule().intervals(),
                c.currentCloseMin() == null ? null : TimeText.format(c.currentCloseMin()),
                c.availability().unavailableIntervals(), c.extendable(),
                available ? transport.stopCount() : null, available ? transport.radiusMeters() : null);
    }

    static String factsJson(Facts facts) {
        return MAPPER.writeValueAsString(facts);
    }

    /** LLM에 넘긴 입력 근거의 SHA-256(64자 hex). */
    static String sourceHash(String factsJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(factsJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 출력 JSON 스키마(strict). 길이·개수·시각 범위는 스키마 키워드 지원이 불확실해서 서버에서 검증한다.
     */
    static Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("proposedClosingTime", "alternatives", "integratedSuggestion"),
                "properties", Map.of(
                        "proposedClosingTime", Map.of("type", List.of("string", "null")),
                        "alternatives", Map.of("type", "array", "items", Map.of("type", "string")),
                        "integratedSuggestion", Map.of("type", "string")));
    }
}
