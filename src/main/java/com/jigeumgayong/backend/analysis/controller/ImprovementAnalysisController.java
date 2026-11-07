package com.jigeumgayong.backend.analysis.controller;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisRequest;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisRequest.CriteriaRequest;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService.AnalysisResult;
import com.jigeumgayong.backend.global.ApiPaths;
import com.jigeumgayong.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A04 공간별 불일치 분석 및 AI 개선안 (06 A04). 캐시를 새로 만들어도 201이 아니라 200이다. */
@RestController
@RequiredArgsConstructor
public class ImprovementAnalysisController {

    private final ImprovementAnalysisService service;

    @PostMapping(value = ApiPaths.BASE + "/improvement-analyses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ImprovementAnalysisResponse> analyze(@Valid @RequestBody ImprovementAnalysisRequest request) {
        CriteriaRequest c = request.criteria();
        AnalysisResult result = service.analyze(c.keyword(), c.district(), c.facilityTypeIds(),
                c.dayOfWeek(), c.startTime(), c.endTime());
        return ApiResponse.ok(result.data(), result.warnings());
    }
}
