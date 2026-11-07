package com.jigeumgayong.backend.facility;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.controller.FacilitySearchOptionController;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse.DayOption;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse.FacilityTypeOption;
import com.jigeumgayong.backend.facility.domain.District;
import com.jigeumgayong.backend.facility.service.FacilitySearchOptionService;
import com.jigeumgayong.backend.global.error.GlobalExceptionHandler;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FacilitySearchOptionControllerTest {

    static final String URL = "/api/v1/facility-search-options";

    FacilitySearchOptionService service = mock(FacilitySearchOptionService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FacilitySearchOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static FacilitySearchOptionsResponse response(List<FacilityTypeOption> types) {
        List<DayOption> days = IntStream.range(0, 7)
                .mapToObj(i -> new DayOption(i, List.of("월", "화", "수", "목", "금", "토", "일").get(i)))
                .toList();
        return new FacilitySearchOptionsResponse(types, District.ALL, days, 6, "Asia/Seoul");
    }

    @Test
    void ok_response_has_contract_structure() throws Exception {
        when(service.getOptions()).thenReturn(response(List.of(
                new FacilityTypeOption(1, "도서관", null),
                new FacilityTypeOption(3, "생활체육시설", "/img/a.png"))));

        mvc.perform(get(URL).header("X-Request-Id", "req-a01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityTypes.length()").value(2))
                .andExpect(jsonPath("$.data.facilityTypes[0].typeId").value(1))
                .andExpect(jsonPath("$.data.facilityTypes[0].name").value("도서관"))
                .andExpect(jsonPath("$.data.facilityTypes[0].defaultImageUrl").value((Object) null))
                .andExpect(jsonPath("$.data.facilityTypes[1].typeId").value(3))
                .andExpect(jsonPath("$.data.facilityTypes[1].defaultImageUrl").value("/img/a.png"))
                .andExpect(jsonPath("$.data.facilityTypes[0].chipGroup").doesNotExist())
                .andExpect(jsonPath("$.data.districts.length()").value(3))
                .andExpect(jsonPath("$.data.districts[0]").value("수지구"))
                .andExpect(jsonPath("$.data.daysOfWeek.length()").value(7))
                .andExpect(jsonPath("$.data.daysOfWeek[0].value").value(0))
                .andExpect(jsonPath("$.data.daysOfWeek[0].label").value("월"))
                .andExpect(jsonPath("$.data.daysOfWeek[6].label").value("일"))
                .andExpect(jsonPath("$.data.defaultDayOfWeek").value(6))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.meta.requestId").value("req-a01"))
                .andExpect(jsonPath("$.meta.generatedAt").isString())
                .andExpect(jsonPath("$.meta.warnings").isEmpty());
    }

    @Test
    void empty_facility_types_is_still_200_with_empty_array() throws Exception {
        when(service.getOptions()).thenReturn(response(List.of()));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityTypes").isArray())
                .andExpect(jsonPath("$.data.facilityTypes").isEmpty());
    }

    @Test
    void db_failure_is_503_data_unavailable() throws Exception {
        when(service.getOptions()).thenThrow(new DataAccessResourceFailureException("db down"));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DATA_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.error.requestId").isString());
    }
}
