package com.jigeumgayong.backend.analysis.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** A04 요청 Body (06 A04, 07 3.6). 시간은 24:00을 표현하려고 문자열로 받아 서비스에서 파싱한다. */
public record ImprovementAnalysisRequest(@NotNull @Valid CriteriaRequest criteria) {

    public record CriteriaRequest(String keyword, String district, List<Integer> facilityTypeIds,
                                  @NotNull Integer dayOfWeek, @NotNull String startTime, @NotNull String endTime) {
    }
}
