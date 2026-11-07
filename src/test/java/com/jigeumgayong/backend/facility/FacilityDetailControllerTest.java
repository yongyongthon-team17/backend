package com.jigeumgayong.backend.facility;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.controller.FacilityDetailController;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.Criteria;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.FacilityDetail;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.SpaceDetail;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.FacilityDetailService;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.GlobalExceptionHandler;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FacilityDetailControllerTest {

    static final String URL = "/api/v1/facilities/101";

    FacilityDetailService service = mock(FacilityDetailService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FacilityDetailController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static FacilityDetailResponse sample() {
        var day = new DaySchedule(1, "UNKNOWN", List.of());
        var space = new SpaceDetail("DEFAULT", "시설 전체", day,
                new Availability("UNKNOWN", "WEEKLY_SCHEDULE", false, null), List.of(day));
        var facility = new FacilityDetail("101", 1, "도서관", "수지구", "OPEN_HOURS", "예시 공공도서관", "주소", null,
                new Coordinates(new BigDecimal("37.32"), new BigDecimal("127.1")), null, null,
                LocalDate.of(2026, 10, 31), List.of(), List.of(space));
        return new FacilityDetailResponse(new Criteria(1, "19:00", "21:00"), facility);
    }

    @Test
    void ok_has_contract_structure() throws Exception {
        when(service.detail(eq("101"), eq(1), eq("19:00"), eq("21:00"))).thenReturn(sample());

        mvc.perform(get(URL).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00")
                        .header("X-Request-Id", "req-a03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.dayOfWeek").value(1))
                .andExpect(jsonPath("$.data.facility.facilityId").value("101"))
                .andExpect(jsonPath("$.data.facility.phone").value((Object) null))
                .andExpect(jsonPath("$.data.facility.dataUpdatedAt").value("2026-10-31"))
                .andExpect(jsonPath("$.data.facility.amenities").isArray())
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.unavailableIntervals")
                        .value((Object) null))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours[0].intervals").isEmpty())
                .andExpect(jsonPath("$.meta.requestId").value("req-a03"))
                .andExpect(jsonPath("$.meta.generatedAt").isString())
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"));
    }

    @Test
    void facility_not_found_is_404_with_error_envelope() throws Exception {
        when(service.detail(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        mvc.perform(get(URL).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").isString());
    }

    @Test
    void missing_or_bad_params_are_invalid_request() throws Exception {
        mvc.perform(get(URL).param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("dayOfWeek"));
        mvc.perform(get(URL).param("dayOfWeek", "7").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get(URL).param("dayOfWeek", "abc").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get(URL).param("dayOfWeek", "1").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
