package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 컨텍스트(H2) + JSON 직렬화 + 공통 에러 핸들러까지 확인한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
class FacilityDetailIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired EntityManagerFactory emf;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    FacilityType type;
    int seq = 0;

    @BeforeEach
    void setUp() {
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    private Facility facility(AccessType access) {
        return facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("it-" + (seq++)).district("수지구").accessType(access)
                .name("시설" + seq).address("용인시 수지구")
                .latitude(new BigDecimal("37.322345")).longitude(new BigDecimal("127.098765"))
                .sourceUpdatedAt(LocalDate.of(2026, 10, 31)).build());
    }

    private void space(Facility f, SpaceType t, String label) {
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), t, label));
    }

    private void open(Facility f, SpaceType t, int day, int seqNo, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) day).intervalSeq((short) seqNo).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    private String url(Facility f) {
        return "/api/v1/facilities/" + f.getFacilityId();
    }

    @Test
    void always_open_json() throws Exception {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        space(f, SpaceType.DEFAULT, "시설 전체");

        mvc.perform(get(url(f)).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.endTime").value("21:00"))
                .andExpect(jsonPath("$.data.facility.facilityId").value(String.valueOf(f.getFacilityId())))
                .andExpect(jsonPath("$.data.facility.accessType").value("ALWAYS_OPEN"))
                .andExpect(jsonPath("$.data.facility.coordinates.latitude").value(37.322345))
                .andExpect(jsonPath("$.data.facility.phone").value((Object) null))
                .andExpect(jsonPath("$.data.facility.closedDayNote").value((Object) null))
                .andExpect(jsonPath("$.data.facility.dataUpdatedAt").value("2026-10-31"))
                .andExpect(jsonPath("$.data.facility.amenities").isEmpty())
                .andExpect(jsonPath("$.data.facility.spaces.length()").value(1))
                .andExpect(jsonPath("$.data.facility.spaces[0].spaceType").value("DEFAULT"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.basis").value("ACCESS_TYPE"))
                .andExpect(jsonPath("$.data.facility.spaces[0].selectedSchedule.intervals[0].closeTime")
                        .value("24:00"))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours.length()").value(7))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours[6].dayOfWeek").value(6))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"));
    }

    @Test
    void reservation_json() throws Exception {
        Facility f = facility(AccessType.RESERVATION);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, 1, 1, 540, 1320);

        mvc.perform(get(url(f)).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.basis").value("WEEKLY_SCHEDULE"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.requiresReservation").value(true))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours[0].state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours[0].intervals").isEmpty());
    }

    @Test
    void lunch_break_and_per_space_json() throws Exception {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.GENERAL, "종합자료실");
        space(f, SpaceType.READING, "열람실");
        open(f, SpaceType.GENERAL, 1, 1, 540, 720);
        open(f, SpaceType.GENERAL, 1, 2, 780, 1080);
        open(f, SpaceType.READING, 1, 1, 540, 1320);
        amenityRepository.save(new FacilityAmenity(f.getFacilityId(), AmenityCode.PARKING, "주차 120면"));

        mvc.perform(get(url(f)).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facility.amenities[0]").value("열람실"))
                .andExpect(jsonPath("$.data.facility.amenities[1]").value("주차 120면"))
                .andExpect(jsonPath("$.data.facility.spaces.length()").value(2))
                .andExpect(jsonPath("$.data.facility.spaces[0].spaceType").value("GENERAL"))
                .andExpect(jsonPath("$.data.facility.spaces[0].selectedSchedule.intervals.length()").value(2))
                .andExpect(jsonPath("$.data.facility.spaces[0].selectedSchedule.intervals[1].openTime")
                        .value("13:00"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.unavailableIntervals[0].startTime")
                        .value("19:00"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.unavailableIntervals[0].endTime")
                        .value("21:00"))
                .andExpect(jsonPath("$.data.facility.spaces[1].spaceType").value("READING"))
                .andExpect(jsonPath("$.data.facility.spaces[1].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.facility.spaces[1].availability.unavailableIntervals").isEmpty());
    }

    @Test
    void unknown_json_keeps_null_unavailable_intervals() throws Exception {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");

        mvc.perform(get(url(f)).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.facility.spaces[0].selectedSchedule.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.facility.spaces[0].selectedSchedule.intervals").isEmpty())
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.facility.spaces[0].availability.unavailableIntervals")
                        .value((Object) null))
                .andExpect(jsonPath("$.data.facility.spaces[0].weeklyHours.length()").value(7));
    }

    @Test
    void query_count_is_fixed_regardless_of_data_size() throws Exception {
        Facility small = facility(AccessType.OPEN_HOURS);
        space(small, SpaceType.DEFAULT, "시설 전체");
        Facility big = facility(AccessType.OPEN_HOURS);
        space(big, SpaceType.GENERAL, "종합자료실");
        space(big, SpaceType.READING, "열람실");
        for (int day = 0; day < 7; day++) {
            open(big, SpaceType.GENERAL, day, 1, 540, 720);
            open(big, SpaceType.GENERAL, day, 2, 780, 1080);
            open(big, SpaceType.READING, day, 1, 540, 1320);
        }
        amenityRepository.save(new FacilityAmenity(big.getFacilityId(), AmenityCode.PARKING, null));
        amenityRepository.save(new FacilityAmenity(big.getFacilityId(), AmenityCode.RENTAL_ROOM, null));

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        for (Facility f : new Facility[] { small, big }) {
            stats.clear();
            mvc.perform(get(url(f)).param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                    .andExpect(status().isOk());
            // 시설(+유형), 공간, 운영시간, 편의시설
            assertThat(stats.getPrepareStatementCount()).isEqualTo(4);
        }
    }

    @Test
    void errors_use_common_envelope() throws Exception {
        mvc.perform(get("/api/v1/facilities/999999999").param("dayOfWeek", "1")
                        .param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").isString());
        mvc.perform(get("/api/v1/facilities/abc").param("dayOfWeek", "1")
                        .param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("facilityId"));
        mvc.perform(get("/api/v1/facilities/99999999999999999999").param("dayOfWeek", "1")
                        .param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/facilities/1").param("dayOfWeek", "7")
                        .param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/facilities/1").param("dayOfWeek", "1")
                        .param("startTime", "21:00").param("endTime", "19:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
        // 존재하지 않는 경로는 기존대로 NOT_FOUND
        mvc.perform(get("/api/v1/facilities/1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
