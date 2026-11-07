package com.jigeumgayong.backend.analysis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.analysis.controller.ImprovementAnalysisController;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService.AnalysisResult;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Criteria;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import com.jigeumgayong.backend.global.error.GlobalExceptionHandler;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.global.response.WarningCode;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImprovementAnalysisControllerTest {

    static final String URL = "/api/v1/improvement-analyses";
    static final String BODY = """
            {"criteria":{"keyword":null,"district":"수지구","facilityTypeIds":[1],
             "dayOfWeek":1,"startTime":"19:00","endTime":"21:00"}}""";

    ImprovementAnalysisService service = mock(ImprovementAnalysisService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ImprovementAnalysisController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static AnalysisResult empty() {
        var data = new ImprovementAnalysisResponse(
                new Criteria(null, "수지구", List.of(1), 1, "19:00", "21:00"), 0, 0, 0, List.of(),
                ImprovementAnalysisResponse.DISCLAIMER);
        return new AnalysisResult(data, List.of(Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY, "정규 운영시간 기준입니다.")));
    }

    @Test
    void 정상_요청은_200_공통_봉투다() throws Exception {
        when(service.analyze(any(), eq("수지구"), eq(List.of(1)), eq(1), eq("19:00"), eq("21:00")))
                .thenReturn(empty());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.district").value("수지구"))
                .andExpect(jsonPath("$.data.candidateCount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.disclaimer").isString())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"));
    }

    @Test
    void criteria나_필수_필드가_없으면_400() throws Exception {
        for (String body : new String[] { "{}", "{\"criteria\":null}",
                "{\"criteria\":{\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}",
                "{\"criteria\":{\"dayOfWeek\":1,\"endTime\":\"21:00\"}}" }) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.requestId").isString());
        }
        verifyNoInteractions(service);
    }

    @Test
    void 손상된_JSON과_잘못된_타입은_400() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\":{\"dayOfWeek\":\"월\",\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void 서비스의_검증_오류는_공통_오류_형식으로_변환된다() throws Exception {
        when(service.analyze(any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_TIME_RANGE,
                        ErrorCode.INVALID_TIME_RANGE.defaultMessage(),
                        List.of(new ErrorDetail("endTime", "MUST_BE_AFTER_START_TIME"))));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"))
                .andExpect(jsonPath("$.error.details[0].field").value("endTime"));
    }

    @Test
    void JSON이_아닌_Content_Type은_415() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        verifyNoInteractions(service);
    }

    @Test
    void GET은_405() throws Exception {
        mvc.perform(get(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 선택_필드를_생략해도_서비스로_null이_전달된다() throws Exception {
        when(service.analyze(any(), any(), any(), anyInt(), anyString(), anyString())).thenReturn(empty());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\":{\"dayOfWeek\":1,\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}"))
                .andExpect(status().isOk());

        verify(service).analyze(eq(null), eq(null), eq(null), eq(1), eq("19:00"), eq("21:00"));
    }
}
