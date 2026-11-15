package com.jigeumgayong.backend.busarrival.controller;

import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse;
import com.jigeumgayong.backend.busarrival.service.BusArrivalService;
import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A06 시설 주변 실시간 버스 도착 정보 (06 A06). */
@RestController
@RequiredArgsConstructor
public class BusArrivalController {

    private final BusArrivalService service;

    @GetMapping(ApiPaths.BASE + "/facilities/{facilityId}/bus-arrivals")
    public ApiResponse<BusArrivalResponse> get(@PathVariable String facilityId) {
        return ApiResponse.ok(service.get(facilityId));
    }
}
