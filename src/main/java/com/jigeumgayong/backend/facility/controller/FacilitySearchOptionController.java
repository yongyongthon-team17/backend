package com.jigeumgayong.backend.facility.controller;

import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse;
import com.jigeumgayong.backend.facility.service.FacilitySearchOptionService;
import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A01 검색 선택지 조회 */
@RestController
@RequiredArgsConstructor
public class FacilitySearchOptionController {

    private final FacilitySearchOptionService service;

    @GetMapping(ApiPaths.BASE + "/facility-search-options")
    public ApiResponse<FacilitySearchOptionsResponse> getSearchOptions() {
        return ApiResponse.ok(service.getOptions());
    }
}