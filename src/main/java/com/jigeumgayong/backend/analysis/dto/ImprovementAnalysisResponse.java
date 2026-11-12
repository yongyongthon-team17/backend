package com.jigeumgayong.backend.analysis.dto;

import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Criteria;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import java.time.OffsetDateTime;
import java.util.List;

/** A04 응답 data (06 A04). DaySchedule·Availability는 A02/A03과 같은 타입(intervals[])을 재사용한다. */
public record ImprovementAnalysisResponse(
        Criteria criteria,
        int candidateCount,
        int facilityCount,
        int unknownSpaceCount,
        List<ImprovementItem> items,
        String disclaimer) {

    public static final String DISCLAIMER = "AI 제안은 참고 의견이며 공식 운영시간이나 확정된 정책이 아닙니다. "
            + "실제 이용 전 공식 홈페이지 또는 전화로 확인하세요.";

    public record ImprovementItem(
            FacilityBase facility,
            String spaceType,
            String spaceLabel,
            DaySchedule selectedSchedule,
            Availability availability,
            String currentClosingTime,
            String proposedClosingTime,
            String proposalStatus,
            String proposalReason,
            List<String> alternatives,
            IntegratedSuggestion integratedSuggestion,
            Accessibility transport, // A05와 같은 타입. AVAILABLE / NO_DATA, 스위치 off·변환 예외 시 NOT_ENABLED
            WeakTimeAnalysis weakTimeAnalysis,
            String suggestionId,
            CacheInfo cache,
            List<String> limitations) {
    }

    /** 07 3.7 FacilityBase */
    public record FacilityBase(String facilityId, int typeId, String typeName, String district, String accessType,
                               String name, String address, String imageUrl, Coordinates coordinates) {
    }

    /** status: HIT / CREATED / NOT_STORED / UNAVAILABLE. createdAt은 캐시 행의 updated_at(현재 내용이 만들어진 시각) */
    public record CacheInfo(String status, OffsetDateTime createdAt) {
    }

    /**
     * status: COMPLETE(교통 AVAILABLE + 문장에 정류장 수 포함) / PARTIAL / UNAVAILABLE. 응답 시 판정하며 DB에 저장하지 않는다.
     * 같은 시설의 항목들은 같은 문장을 갖는다. 부족한 점이 없어 LLM을 부르지 않은 경우 UNAVAILABLE이지만 text에 고정 문구가 있다.
     */
    public record IntegratedSuggestion(String status, String text) {
    }

    /** status: NOT_SUPPORTED, periods: [] */
    public record WeakTimeAnalysis(String status, List<WeakTimePeriod> periods) {
    }

    public record WeakTimePeriod(int dayOfWeek, String startTime, String endTime, String cause, String evidence) {
    }
}
