package com.jigeumgayong.backend.transport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.GlobalExceptionHandler;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import com.jigeumgayong.backend.transport.controller.TransportAccessibilityController;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import com.jigeumgayong.backend.transport.service.TransportAccessibilityService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransportAccessibilityControllerTest {

    static final String URL = "/api/v1/facilities/101/transport-accessibility";

    TransportAccessibilityService service = mock(TransportAccessibilityService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TransportAccessibilityController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void AVAILABLE_응답과_등급미정_경고() throws Exception {
        when(service.get("101")).thenReturn(new TransportAccessibilityResponse("101",
                new Accessibility(Status.AVAILABLE, 500, 4, null, List.of("등급 기준은 추가 논의가 필요합니다."))));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityId").value("101"))
                .andExpect(jsonPath("$.data.accessibility.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.accessibility.radiusMeters").value(500))
                .andExpect(jsonPath("$.data.accessibility.stopCount").value(4))
                .andExpect(jsonPath("$.data.accessibility.grade").value((Object) null))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("GRADE_RULE_NOT_DEFINED"))
                .andExpect(jsonPath("$.meta.warnings[0].facilityId").value("101"));
    }

    @Test
    void NO_DATA_응답과_자료없음_경고() throws Exception {
        when(service.get("101")).thenReturn(new TransportAccessibilityResponse("101",
                new Accessibility(Status.NO_DATA, 500, null, null, List.of("500m 정류장 집계값이 적재되지 않았습니다."))));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessibility.status").value("NO_DATA"))
                .andExpect(jsonPath("$.data.accessibility.stopCount").value((Object) null))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("TRANSPORT_DATA_UNAVAILABLE"));
    }

    @Test
    void 시설이_없으면_404() throws Exception {
        when(service.get("101")).thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        mvc.perform(get(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
    }

    // 기능 on/off 스위치를 쓰지 않아 503 FEATURE_NOT_ENABLED 테스트는 주석 처리했다
    // @Test
    // void 기능_비활성이면_503() throws Exception {
    //     when(service.get("101")).thenThrow(new BusinessException(ErrorCode.FEATURE_NOT_ENABLED));
    //
    //     mvc.perform(get(URL))
    //             .andExpect(status().isServiceUnavailable())
    //             .andExpect(jsonPath("$.error.code").value("FEATURE_NOT_ENABLED"));
    // }
}
