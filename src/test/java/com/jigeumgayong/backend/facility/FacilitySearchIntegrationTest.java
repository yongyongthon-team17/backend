package com.jigeumgayong.backend.facility;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 컨텍스트(H2) + JSON 직렬화 + 공통 에러 핸들러까지 한 번에 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class FacilitySearchIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;

    Long facilityId;
    Short typeId;

    @BeforeEach
    void setUp() {
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType t = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        typeId = t.getTypeId();
        Facility f = facilityRepository.save(Facility.builder().type(t).sourceType(SourceType.MANUAL)
                .sourceKey("it-1").district("수지구").accessType(AccessType.OPEN_HOURS)
                .name("통합도서관").address("용인시 수지구").latitude(new BigDecimal("37.322345"))
                .longitude(new BigDecimal("127.098765")).build());
        facilityId = f.getFacilityId();
        spaceRepository.save(new FacilitySpace(facilityId, SpaceType.GENERAL, "종합자료실"));
        spaceRepository.save(new FacilitySpace(facilityId, SpaceType.READING, "열람실"));
        hourRepository.save(OperatingHour.builder().facilityId(facilityId).spaceType(SpaceType.GENERAL)
                .dayOfWeek((short) 1).dayState(DayState.OPEN).openMin((short) 540).closeMin((short) 1080).build());
        hourRepository.save(OperatingHour.builder().facilityId(facilityId).spaceType(SpaceType.READING)
                .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) 720).build());
        hourRepository.save(OperatingHour.builder().facilityId(facilityId).spaceType(SpaceType.READING)
                .dayOfWeek((short) 1).intervalSeq((short) 2).dayState(DayState.OPEN)
                .openMin((short) 720).closeMin((short) 1320).build());
    }

    @Test
    void full_stack_json_shape() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("district", "수지구")
                        .param("facilityTypeIds", String.valueOf(typeId))
                        .param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.keyword").value((Object) null))
                .andExpect(jsonPath("$.data.criteria.district").value("수지구"))
                .andExpect(jsonPath("$.data.criteria.dayOfWeek").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].facilityId").value(String.valueOf(facilityId)))
                .andExpect(jsonPath("$.data.items[0].typeName").value("도서관"))
                .andExpect(jsonPath("$.data.items[0].imageUrl").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].coordinates.latitude").value(37.322345))
                .andExpect(jsonPath("$.data.items[0].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].spaceType").value("GENERAL"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.unavailableIntervals[0].startTime")
                        .value("19:00"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[1].spaceType").value("READING"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[1].selectedSchedule.state").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[1].selectedSchedule.intervals.length()")
                        .value(2))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[1].selectedSchedule.intervals[1].closeTime")
                        .value("22:00"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[1].availability.basis")
                        .value("WEEKLY_SCHEDULE"))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.availableCount").value(1))
                .andExpect(jsonPath("$.data.matchedSpaceCount").value(1))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"));
    }

    @Test
    void unavailable_and_unknown_facilities_are_returned_with_status_and_sorted_last() throws Exception {
        FacilityType t = typeRepository.findById(typeId).orElseThrow();
        // 화요일(1) 19~21시 기준: 18시 마감 → 이용 불가, 운영시간 행 없음 → 확인 필요
        Facility early = facilityRepository.save(Facility.builder().type(t).sourceType(SourceType.MANUAL)
                .sourceKey("it-4").district("수지구").accessType(AccessType.OPEN_HOURS)
                .name("일찍닫는곳").address("용인시 수지구").latitude(new BigDecimal("37.3"))
                .longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(early.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(early.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) 1).dayState(DayState.OPEN).openMin((short) 540).closeMin((short) 1080).build());
        Facility unknown = facilityRepository.save(Facility.builder().type(t).sourceType(SourceType.MANUAL)
                .sourceKey("it-5").district("수지구").accessType(AccessType.OPEN_HOURS)
                .name("시간모르는곳").address("용인시 수지구").latitude(new BigDecimal("37.3"))
                .longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(unknown.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));

        mvc.perform(get("/api/v1/facilities").param("district", "수지구")
                        .param("dayOfWeek", "1").param("startTime", "19:00").param("endTime", "21:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.availableCount").value(1))
                .andExpect(jsonPath("$.data.items[0].availabilityStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[1].name").value("일찍닫는곳"))
                .andExpect(jsonPath("$.data.items[1].availabilityStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[1].availableSpaces[0].availability.unavailableIntervals[0].endTime")
                        .value("21:00"))
                .andExpect(jsonPath("$.data.items[2].name").value("시간모르는곳"))
                .andExpect(jsonPath("$.data.items[2].availabilityStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[2].availableSpaces[0].availability.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[2].availableSpaces[0].availability.unavailableIntervals")
                        .value((Object) null))
                .andExpect(jsonPath("$.data.unknownSpaceCount").value(1));
    }

    @Test
    void always_open_and_reservation_json() throws Exception {
        FacilityType t = typeRepository.findById(typeId).orElseThrow();
        Facility court = facilityRepository.save(Facility.builder().type(t).sourceType(SourceType.MANUAL)
                .sourceKey("it-2").district("수지구").accessType(AccessType.ALWAYS_OPEN)
                .name("상시코트").address("용인시 수지구").latitude(new BigDecimal("37.3"))
                .longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(court.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        Facility rsv = facilityRepository.save(Facility.builder().type(t).sourceType(SourceType.MANUAL)
                .sourceKey("it-3").district("수지구").accessType(AccessType.RESERVATION)
                .name("예약체육관").address("용인시 수지구").latitude(new BigDecimal("37.3"))
                .longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(rsv.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(rsv.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) 1).dayState(DayState.OPEN).openMin((short) 540).closeMin((short) 1440).build());

        mvc.perform(get("/api/v1/facilities").param("keyword", "상시코트")
                        .param("dayOfWeek", "1").param("startTime", "20:00").param("endTime", "24:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].accessType").value("ALWAYS_OPEN"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.basis").value("ACCESS_TYPE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.requiresReservation")
                        .value(false))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.state").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals[0].openTime")
                        .value("00:00"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals[0].closeTime")
                        .value("24:00"));

        mvc.perform(get("/api/v1/facilities").param("keyword", "예약체육관")
                        .param("dayOfWeek", "1").param("startTime", "20:00").param("endTime", "24:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].accessType").value("RESERVATION"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.basis")
                        .value("WEEKLY_SCHEDULE"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].availability.requiresReservation")
                        .value(true))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals[0].openTime")
                        .value("09:00"))
                .andExpect(jsonPath("$.data.items[0].availableSpaces[0].selectedSchedule.intervals[0].closeTime")
                        .value("24:00"));
    }

    @Test
    void errors_use_common_envelope() throws Exception {
        mvc.perform(get("/api/v1/facilities").param("dayOfWeek", "7")
                        .param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/facilities").param("district", "서울").param("dayOfWeek", "1")
                        .param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/facilities").param("dayOfWeek", "1")
                        .param("startTime", "12:00").param("endTime", "10:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"))
                .andExpect(jsonPath("$.error.requestId").isString());
        mvc.perform(get("/api/v1/facilities").param("facilityTypeIds", "9999").param("dayOfWeek", "1")
                        .param("startTime", "10:00").param("endTime", "12:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
