package com.jigeumgayong.backend.facility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.controller.FacilitySearchController;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Criteria;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Item;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.SpaceAvailability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.FacilitySearchService;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.GlobalExceptionHandler;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FacilitySearchControllerTest {

    static final String URL = "/api/v1/facilities";

    FacilitySearchService service = mock(FacilitySearchService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FacilitySearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static FacilitySearchResponse sample() {
        var space = new SpaceAvailability("READING", "열람실",
                new DaySchedule(1, "OPEN", List.of(new TimeInterval("09:00", "22:00"))),
                new Availability("AVAILABLE", "WEEKLY_SCHEDULE", false, List.of()));
        var item = new Item("101", 1, "도서관", "수지구", "OPEN_HOURS", "예시 공공도서관", "주소", null,
                new Coordinates(new BigDecimal("37.32"), new BigDecimal("127.1")), null, "AVAILABLE", List.of(space));
        return new FacilitySearchResponse(
                new Criteria(null, "수지구", List.of(1), 1, "19:00", "21:00"), List.of(item), 1, 1, 1, 0);
    }

    @Test
    void ok_has_contract_structure() throws Exception {
        when(service.search(any(), eq("수지구"), eq(List.of(1)), eq(1), eq("19:00"), eq("21:00")))
                .thenReturn(sample());

        mvc.perform(get(URL).param("district", "수지구").param("facilityTypeIds", "1")
                        .param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00")
                        .header("X-Request-Id", "req-a02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.keyword").value((Object) null))
                .andExpect(jsonPath("$.data.criteria.facilityTypeIds[0]").value(1))
                .andExpect(jsonPath("$.data.items[0].facilityId").value("101"))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].coordinates.latitude").value(37.32))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals[0].closeTime")
                        .value("22:00"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.unavailableIntervals")
                        .isEmpty())
                .andExpect(jsonPath("$.data.items[0].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.availableCount").value(1))
                .andExpect(jsonPath("$.data.matchedSpaceCount").value(1))
                .andExpect(jsonPath("$.data.unknownSpaceCount").value(0))
                .andExpect(jsonPath("$.meta.requestId").value("req-a02"))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"))
                .andExpect(jsonPath("$.meta.warnings[0].facilityId").value((Object) null));
    }

    @Test
    void no_result_is_200_with_empty_items() throws Exception {
        when(service.search(any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(new FacilitySearchResponse(
                        new Criteria(null, null, List.of(), 0, "10:00", "12:00"), List.of(), 0, 0, 0, 0));

        mvc.perform(get(URL).param("dayOfWeek", "0").param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    void missing_required_params_are_invalid_request() throws Exception {
        mvc.perform(get(URL).param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("dayOfWeek"));
        mvc.perform(get(URL).param("dayOfWeek", "0").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get(URL).param("dayOfWeek", "0").param("startTime", "10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void bad_day_of_week_is_invalid_request() throws Exception {
        for (String day : List.of("7", "-1", "abc")) {
            mvc.perform(get(URL).param("dayOfWeek", day).param("startTime", "10:00").param("endTime", "12:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void time_errors_from_service_are_invalid_time_range() throws Exception {
        when(service.search(any(), any(), any(), anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    TimeText.parseRange(inv.getArgument(4), inv.getArgument(5));
                    return sample();
                });

        for (String[] t : new String[][] { { "9:00", "12:00" }, { "10:00", "10:00" }, { "11:00", "10:00" },
                { "10:00", "25:00" } }) {
            mvc.perform(get(URL).param("dayOfWeek", "0").param("startTime", t[0]).param("endTime", t[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
        }
    }

    @Test
    void invalid_district_is_invalid_request() throws Exception {
        when(service.search(any(), eq("서울"), any(), anyInt(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_REQUEST));

        mvc.perform(get(URL).param("district", "서울").param("dayOfWeek", "0")
                        .param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
