package com.jigeumgayong.backend.facility.controller;

import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse;
import com.jigeumgayong.backend.facility.service.FacilityDetailService;
import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.global.response.WarningCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A03 시설 상세 조회 (06 A03). 시간 조건에 맞지 않아도 시설이 있으면 200이다. */
@RestController
@RequiredArgsConstructor
public class FacilityDetailController {

    private static final Warning WEEKLY_ONLY = Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY,
            "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.");

    private final FacilityDetailService service;

    @GetMapping(ApiPaths.BASE + "/facilities/{facilityId}")
    public ApiResponse<FacilityDetailResponse> detail(
            // 형식 검증(양수 10진수)은 서비스에서 한다. BIGINT는 문자열로 주고받는다.
            @PathVariable String facilityId,
            @RequestParam @Min(0) @Max(6) Integer dayOfWeek,
            // 24:00을 표현하려고 문자열로 받아 서비스에서 직접 파싱한다 (A02와 동일)
            @RequestParam String startTime,
            @RequestParam String endTime) {
        return ApiResponse.ok(service.detail(facilityId, dayOfWeek, startTime, endTime), List.of(WEEKLY_ONLY));
    }
}
