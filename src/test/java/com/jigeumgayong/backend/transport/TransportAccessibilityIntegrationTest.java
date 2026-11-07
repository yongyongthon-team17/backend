package com.jigeumgayong.backend.transport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 컨텍스트(H2)에서 적재값 → 응답 JSON까지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class TransportAccessibilityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;

    FacilityType type;

    @BeforeEach
    void setUp() {
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    private Long save(String key, Integer busStopCount) {
        return facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey(key).district("수지구").name("시설" + key).address("용인시 수지구")
                .latitude(new BigDecimal("37.322345")).longitude(new BigDecimal("127.098765"))
                .busStopCount(busStopCount).build()).getFacilityId();
    }

    @Test
    void 적재된_정류장_수가_응답에_나온다() throws Exception {
        Long id = save("t-1", 4);

        mvc.perform(get("/api/v1/facilities/{id}/transport-accessibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.accessibility.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.accessibility.stopCount").value(4))
                .andExpect(jsonPath("$.data.accessibility.grade").value((Object) null))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("GRADE_RULE_NOT_DEFINED"));
    }

    @Test
    void 미적재_시설은_NO_DATA이고_0으로_보이지_않는다() throws Exception {
        Long id = save("t-2", null);

        mvc.perform(get("/api/v1/facilities/{id}/transport-accessibility", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessibility.status").value("NO_DATA"))
                .andExpect(jsonPath("$.data.accessibility.stopCount").value((Object) null))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("TRANSPORT_DATA_UNAVAILABLE"));
    }

    @Test
    void 없는_시설은_404_형식오류는_400() throws Exception {
        mvc.perform(get("/api/v1/facilities/999999/transport-accessibility"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
        mvc.perform(get("/api/v1/facilities/abc/transport-accessibility"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
