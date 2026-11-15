package com.jigeumgayong.backend.busarrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.support.FakeBusArrivalClient;
import com.jigeumgayong.backend.support.FakeBusArrivalConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 스위치 기본값(false)이면 키가 있어도 외부 호출 없이 NOT_ENABLED. 없는 시설은 스위치와 무관하게 404. */
@SpringBootTest(properties = "app.bus.api-key=test-key")
@AutoConfigureMockMvc
@Import(FakeBusArrivalConfig.class)
class BusArrivalSwitchOffTest {

    @Autowired MockMvc mvc;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FakeBusArrivalClient fake;

    @Test
    void 스위치가_꺼져_있으면_외부호출_없이_200_NOT_ENABLED() throws Exception {
        fake.reset();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType type = typeRepository.save(
                FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        Long id = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("off-1").district("처인구").name("시설").address("용인시 처인구")
                .latitude(new BigDecimal("37.278217")).longitude(new BigDecimal("127.154183"))
                .build()).getFacilityId();

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_ENABLED"))
                .andExpect(jsonPath("$.data.stops.length()").value(0));
        mvc.perform(get("/api/v1/facilities/999999/bus-arrivals"))
                .andExpect(status().isNotFound());
        assertThat(fake.calls()).isZero();
    }
}
