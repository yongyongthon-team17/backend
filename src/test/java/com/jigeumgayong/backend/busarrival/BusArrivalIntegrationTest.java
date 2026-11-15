package com.jigeumgayong.backend.busarrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 컨텍스트(H2) + seed/bus_stops.csv + 가짜 클라이언트로 A06 응답 JSON까지 확인한다.
 * 시설 좌표는 CSV 정류장 GGB228001782 위치라 이 정류장이 거리 0m로 가장 가깝다.
 */
@SpringBootTest(properties = {
        "app.bus.realtime-enabled=true",
        "app.bus.api-key=test-key",
        "app.bus.timeout=500ms"
})
@AutoConfigureMockMvc
@Import(FakeBusArrivalConfig.class)
class BusArrivalIntegrationTest {

    static final String NEAREST = "GGB228001782";

    @Autowired MockMvc mvc;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FakeBusArrivalClient fake;

    FacilityType type;

    @BeforeEach
    void setUp() {
        fake.reset();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    private Long save(String key, String lat, String lng) {
        return facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey(key).district("처인구").name("시설" + key).address("용인시 처인구")
                .latitude(new BigDecimal(lat)).longitude(new BigDecimal(lng)).build()).getFacilityId();
    }

    private Long saveNearStops(String key) {
        return save(key, "37.278217", "127.154183");
    }

    @Test
    void 정상_가까운_정류장_최대3곳과_노선_최대5개를_남은시간순으로_준다() throws Exception {
        Long id = saveNearStops("b-1");

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facilityId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.radiusMeters").value(500))
                .andExpect(jsonPath("$.data.stops.length()").value(lessThanOrEqualTo(3)))
                .andExpect(jsonPath("$.data.stops[0].stopId").value(NEAREST))
                .andExpect(jsonPath("$.data.stops[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.data.stops[0].arrivals.length()").value(5))
                .andExpect(jsonPath("$.data.stops[0].arrivals[0].routeName").value("10"))
                .andExpect(jsonPath("$.data.stops[0].arrivals[0].minutesLeft").value(2))
                .andExpect(jsonPath("$.data.stops[0].arrivals[0].stopsLeft").value(1))
                // 남은 시간이 없는 노선(66-4)은 맨 뒤라 5개 제한에서 빠진다
                .andExpect(jsonPath("$.data.stops[0].arrivals[4].routeName").value("820"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void 반경_안에_정류장이_없으면_외부호출_없이_AVAILABLE_빈목록() throws Exception {
        // 용인 밖(서해) 좌표: CSV 정류장과 500m 이상 떨어짐
        Long id = save("b-2", "37.000000", "126.000000");

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.stops.length()").value(0));
        assertThat(fake.calls()).isZero();
    }

    @Test
    void 외부API가_모두_실패하면_200_UNAVAILABLE_빈목록이고_캐시하지_않는다() throws Exception {
        Long id = saveNearStops("b-3");
        fake.failAll();

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.stops.length()").value(0));
        int first = fake.calls();

        fake.reset();
        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
        assertThat(fake.calls()).isEqualTo(first);
    }

    @Test
    void 일부_정류장만_실패하면_AVAILABLE이고_그_정류장_도착목록만_비운다() throws Exception {
        Long id = saveNearStops("b-4");
        fake.fail(NEAREST);

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.stops[0].stopId").value(NEAREST))
                .andExpect(jsonPath("$.data.stops[0].arrivals.length()").value(0))
                .andExpect(jsonPath("$.data.stops[1].arrivals.length()").value(5));
    }

    @Test
    void 타임아웃이면_제한시간_안에_200_UNAVAILABLE() throws Exception {
        Long id = saveNearStops("b-5");
        fake.delayAll(2_000);

        long start = System.nanoTime();
        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.stops.length()").value(0));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // 정류장들은 병렬 호출이라 전체가 제한 시간(500ms) 근처에서 끝난다
        assertThat(elapsedMs).isLessThan(1_500);
    }

    @Test
    void 캐시_HIT이면_외부API를_다시_부르지_않는다() throws Exception {
        Long id = saveNearStops("b-6");

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
        int afterFirst = fake.calls();
        assertThat(afterFirst).isBetween(1, 3);

        mvc.perform(get("/api/v1/facilities/{id}/bus-arrivals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.stops[0].arrivals.length()").value(5));
        assertThat(fake.calls()).isEqualTo(afterFirst);
    }

    @Test
    void 없는_시설은_404_형식오류는_400() throws Exception {
        mvc.perform(get("/api/v1/facilities/999999/bus-arrivals"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
        mvc.perform(get("/api/v1/facilities/abc/bus-arrivals"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(fake.calls()).isZero();
    }
}
