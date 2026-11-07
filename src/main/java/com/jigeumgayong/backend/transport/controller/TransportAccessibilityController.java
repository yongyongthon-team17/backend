package com.jigeumgayong.backend.transport.controller;

import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.global.response.WarningCode;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import com.jigeumgayong.backend.transport.service.TransportAccessibilityService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A05 시설 교통 접근성 조회 (06 A05). */
@RestController
@RequiredArgsConstructor
public class TransportAccessibilityController {

    private final TransportAccessibilityService service;

    @GetMapping(ApiPaths.BASE + "/facilities/{facilityId}/transport-accessibility")
    public ApiResponse<TransportAccessibilityResponse> get(@PathVariable String facilityId) {
        TransportAccessibilityResponse body = service.get(facilityId);
        return ApiResponse.ok(body, warningsOf(body));
    }

    /** NO_DATA면 자료 없음, AVAILABLE이면 등급 기준 미정 경고 (06 A05 예시 문구) */
    private static List<Warning> warningsOf(TransportAccessibilityResponse body) {
        String id = body.facilityId();
        return body.accessibility().status() == Status.NO_DATA
                ? List.of(Warning.of(WarningCode.TRANSPORT_DATA_UNAVAILABLE, "교통 정보를 확인할 수 없습니다.", id))
                : List.of(Warning.of(WarningCode.GRADE_RULE_NOT_DEFINED,
                        "정류장 수는 제공하지만 등급 기준은 미정입니다.", id));
    }
}
