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
            Accessibility transport, // A05와 같은 타입. A04에서는 NOT_ENABLED로만 채운다
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

    /** status: PARTIAL / UNAVAILABLE (교통 근거가 없어 COMPLETE는 쓰지 않는다) */
    public record IntegratedSuggestion(String status, String text) {
    }

    /** status: NOT_SUPPORTED, periods: [] */
    public record WeakTimeAnalysis(String status, List<WeakTimePeriod> periods) {
    }

    public record WeakTimePeriod(int dayOfWeek, String startTime, String endTime, String cause, String evidence) {
    }
}
