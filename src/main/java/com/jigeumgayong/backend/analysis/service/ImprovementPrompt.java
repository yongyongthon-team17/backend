package com.jigeumgayong.backend.analysis.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jigeumgayong.backend.analysis.service.ImprovementObservations.Observation;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 입력(facts)·지시문·출력 스키마·prompt_version. 시설 한 곳을 한 번에 분석한다.
 * 지시문, 스키마, facts 구성, 검증 규칙 중 하나라도 바꾸면 PROMPT_VERSION을 올린다
 * (그러면 IMPROVEMENT 캐시만 다음 조회 때 재생성된다). 모델 변경은 올리지 않는다.
 */
final class ImprovementPrompt {

    static final String PROMPT_VERSION = "imp-v5";
    static final String SCHEMA_NAME = "improvement_proposal";
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String[] DAY_LABELS = { "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일" };

    static final String INSTRUCTIONS = """
            당신은 용인시 공공시설의 운영 개선안을 검토하는 보조자입니다.
            사용자가 보낸 JSON의 observations는 서버가 시설 데이터에서 계산한 시설의 "부족한 점"입니다.
            이용 시간과 관계없이 이 관찰만 근거로, 시설이 개선할 수 있는 방안을 제안합니다.
            규칙:
            1. 모든 제안은 observations 중 하나 이상에 근거해야 하며, 근거로 쓴 id를 basedOn에 모두 적습니다. observations에 없는 문제는 제안하지 않습니다.
            2. 입력에 없는 사실을 만들지 않습니다. 인원, 비용, 예산, 효과(%), 이용자 수, 수요, 혼잡도, 배차 간격 등 입력에 없는 숫자는 절대 쓰지 않습니다.
            3. 시각은 입력에 있는 시각이나 proposedClosingTime의 시각만 "HH:mm" 형식으로 씁니다.
            4. 정류장 수는 입력에 busStopCount가 있을 때만 "시설 500m 내 정류장 N개"로 그 값 그대로 쓰고, 입력에 없으면 정류장을 언급하지 않습니다.
               배차, 막차, 첫차, 운행 간격, 도착 시간, 귀가 가능 여부는 만들거나 추정하지 않고, 필요하면 "실제 운행은 별도 확인이 필요합니다"라고만 안내합니다.
            5. 확정된 정책이 아니라 검토할 수 있는 제안으로 표현합니다. (예: "~를 검토할 수 있습니다")
            6. spaces에는 제안이 있는 공간만 넣고 spaceType은 입력의 spaces에 있는 값만 씁니다. 공간마다 alternatives는 0~3개이고, 전체 alternatives는 1개 이상입니다.
            7. 공간의 proposedClosingTime은 그 공간의 extendable이 true일 때만 currentClosingTime보다 늦고 requestEnd 이상인 "HH:mm" 시각이며, requestEnd와 같게 두는 것을 우선합니다. 그 외에는 항상 null입니다.
            8. observations에 REQUEST_GAP이나 CLOSED_ON_REQUEST_DAY가 있으면 그 해소 방안(마감 연장, 조기 개방, 해당 요일 시범 운영)을 먼저 제안합니다.
               HOURS_UNKNOWN이면 운영시간 정보를 확인하고 공개하는 방안을 제안합니다.
            9. requiresReservation이 true이면 integratedSuggestion에 "예약 필요"를 반드시 포함합니다.
            10. integratedSuggestion은 시설 전체의 observations를 묶은 종합 문장이며, 근거 id를 integratedBasedOn에 적습니다. 입력에 busStopCount가 있으면 교통 내용을 한 번 포함합니다.
            11. 한국어 존댓말로 씁니다. alternatives의 각 text는 120자 이내, integratedSuggestion은 1~2문장, 200자 이내.
            12. observations의 PEER_LATER_CLOSING, PEER_OPEN_AT_REQUEST는 같은 행정구의 같은 유형 시설과 비교한 사실입니다.
                이 비교를 근거로 "비슷한 시설들과 견줘 마감을 늦추거나 희망 시간에 운영하는 방안을 검토할 수 있다"처럼 제안할 수 있습니다.
                비교 시설의 수·비율·이름은 입력에 없으므로 쓰지 않고, detail에 있는 시각과 "대부분"·"절반 이상" 표현만 씁니다.
            13. observations의 FLOW_CLOSING, FLOW_SUPPLY_GAP, FLOW_WEEKEND는 경기도 해당 구의 유동인구 통계를 비교한 사실입니다.
                "경기도 ○○구 유동인구 통계를 비교했을 때 ~시까지는 운영하는 것이 좋아 보입니다"처럼 detail의 지역 표현과 시각을 그대로 인용해 제안할 수 있습니다.
                유동 인구는 그 구 전체의 통계이므로 시설의 이용자 수나 수요, 이용 증가를 단정하지 말고, 통계의 연도나 수치는 쓰지 않습니다.
                COMPARED_CLOSING은 같은 행정구 같은 유형 시설의 마감과 유동인구 통계를 함께 비교한 사실이므로, 두 근거를 한 번에 인용해
                "비슷한 시설들과 경기도 ○○구 유동인구 통계를 비교했을 때 ~시까지는 운영하는 것이 좋아 보입니다"처럼 제안합니다.
            JSON 외의 텍스트는 출력하지 않습니다.
            """;

    /** 공간 한 곳의 사실. unavailableIntervals는 운영시간을 모르면 null */
    record SpaceFacts(String spaceType, String spaceLabel, String dayState, List<TimeInterval> openIntervals,
                      String currentClosingTime, List<Gap> unavailableIntervals, boolean extendable) {
    }

    /**
     * LLM에 넘기는 시설 단위 사실. 필드 순서가 곧 JSON 순서이므로 source_hash가 안정적이다.
     * busStopCount·busStopRadiusMeters는 교통이 AVAILABLE일 때만 들어가고(NO_DATA·NOT_ENABLED는 필드 생략),
     * 운영시간·정류장 수·관찰이 바뀌면 source_hash가 달라져 캐시가 재생성된다.
     */
    record Facts(String facilityName, String facilityType, String accessType, boolean requiresReservation,
                 String dayOfWeek, String requestStart, String requestEnd, List<SpaceFacts> spaces,
                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer busStopCount,
                 @JsonInclude(JsonInclude.Include.NON_NULL) Integer busStopRadiusMeters,
                 List<Observation> observations) {
    }

    private ImprovementPrompt() {
    }

    static Facts facts(List<Candidate> spaces, Accessibility transport, List<Observation> observations) {
        Candidate first = spaces.get(0);
        boolean available = transport.status() == Status.AVAILABLE;
        List<SpaceFacts> spaceFacts = spaces.stream().map(c -> new SpaceFacts(c.spaceType().name(), c.spaceLabel(),
                c.selectedSchedule().state(), c.selectedSchedule().intervals(),
                c.currentCloseMin() == null ? null : TimeText.format(c.currentCloseMin()),
                c.availability().unavailableIntervals(), c.extendable())).toList();
        return new Facts(first.facility().getName(), first.facility().getType().getName(),
                first.facility().getAccessType().name(), first.requiresReservation(),
                DAY_LABELS[first.dayOfWeek()], TimeText.format(first.startMin()), TimeText.format(first.endMin()),
                spaceFacts, available ? transport.stopCount() : null, available ? transport.radiusMeters() : null,
                observations);
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
     * 출력 JSON 스키마(strict). 길이·개수·시각 범위·근거 id 유효성은 스키마 키워드 지원이 불확실해서 서버에서 검증한다.
     */
    static Map<String, Object> outputSchema() {
        Map<String, Object> idList = Map.of("type", "array", "items", Map.of("type", "string"));
        Map<String, Object> alternative = strictObject(List.of("text", "basedOn"),
                Map.of("text", Map.of("type", "string"), "basedOn", idList));
        Map<String, Object> space = strictObject(List.of("spaceType", "proposedClosingTime", "alternatives"),
                Map.of("spaceType", Map.of("type", "string"),
                        "proposedClosingTime", Map.of("type", List.of("string", "null")),
                        "alternatives", Map.of("type", "array", "items", alternative)));
        return strictObject(List.of("spaces", "integratedSuggestion", "integratedBasedOn"),
                Map.of("spaces", Map.of("type", "array", "items", space),
                        "integratedSuggestion", Map.of("type", "string"),
                        "integratedBasedOn", idList));
    }

    private static Map<String, Object> strictObject(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
    }
}
