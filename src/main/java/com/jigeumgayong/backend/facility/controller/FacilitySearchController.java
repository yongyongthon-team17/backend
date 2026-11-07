package com.jigeumgayong.backend.facility.controller;

import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse;
import com.jigeumgayong.backend.facility.service.FacilitySearchService;
import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.global.response.WarningCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A02 시간대별 시설 검색 (06 A02). 요일은 0=월 … 6=일 그대로 사용한다. */
@RestController
@RequiredArgsConstructor
public class FacilitySearchController {

    private static final Warning WEEKLY_ONLY = Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY,
            "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.");

    private final FacilitySearchService service;

    @GetMapping(ApiPaths.BASE + "/facilities")
    public ApiResponse<FacilitySearchResponse> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) List<Integer> facilityTypeIds,
            @RequestParam @Min(0) @Max(6) Integer dayOfWeek,
            // 24:00을 표현하려고 문자열로 받아 서비스에서 직접 파싱한다 (형식 오류 = INVALID_TIME_RANGE)
            @RequestParam String startTime,
            @RequestParam String endTime) {
        return ApiResponse.ok(
                service.search(keyword, district, facilityTypeIds, dayOfWeek, startTime, endTime),
                List.of(WEEKLY_ONLY));
    }
}
