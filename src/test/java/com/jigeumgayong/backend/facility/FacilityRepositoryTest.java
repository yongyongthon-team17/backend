package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.District;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilityAmenityId;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilitySpaceId;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.OperatingHourId;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.support.H2PostgresJpaTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@H2PostgresJpaTest
class FacilityRepositoryTest {

    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilityAmenityRepository amenityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    FacilityType library;

    @BeforeEach
    void setUp() {
        library = typeRepository.saveAndFlush(FacilityType.builder()
                .name("도서관").chipGroup(ChipGroup.LIBRARY).defaultImage("/img/library.png").build());
    }

    private Facility newFacility(String sourceKey) {
        return Facility.builder()
                .type(library).sourceType(SourceType.LIB_STD).sourceKey(sourceKey)
                .district(District.SUJI).name("수지도서관").address("용인시 수지구 어딘가 1")
                .latitude(new BigDecimal("37.322345")).longitude(new BigDecimal("127.098765"))
                .phone("031-000-0000").closedDayNote("매월 둘째·넷째 월요일 휴관")
                .busStopCount(0).busCountedAt(LocalDate.of(2026, 11, 1))
                .sourceUpdatedAt(LocalDate.of(2026, 10, 31)).build();
    }

    // ---------- facility_type ----------

    @Test
    void facilityType_왕복과_name_조회() {
        em.clear();

        FacilityType found = typeRepository.findByName("도서관").orElseThrow();

        assertThat(found.getTypeId()).isNotNull();
        assertThat(found.getChipGroup()).isEqualTo("도서관");
        assertThat(found.getDefaultImage()).isEqualTo("/img/library.png");
        assertThat(typeRepository.findByName("없는유형")).isEmpty();
    }

    @Test
    void facilityType_name_중복은_UNIQUE_위반() {
        assertThatThrownBy(() -> typeRepository.saveAndFlush(
                FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chipGroup이_허용값이_아니면_거부한다() {
        assertThatThrownBy(() -> FacilityType.builder().name("x").chipGroup("기타").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- facility ----------

    @Test
    void facility_왕복_좌표_소수점과_기본값() {
        Facility saved = facilityRepository.saveAndFlush(newFacility("L-001"));
        em.clear();

        Facility found = facilityRepository.findById(saved.getFacilityId()).orElseThrow();

        assertThat(found.getType().getName()).isEqualTo("도서관");
        assertThat(found.getAccessType()).isEqualTo(AccessType.OPEN_HOURS); // 기본값
        assertThat(found.getLatitude()).isEqualByComparingTo("37.322345");
        assertThat(found.getLongitude()).isEqualByComparingTo("127.098765");
        assertThat(found.getBusStopCount()).isZero(); // 0과 null은 다르다 (R14)
        assertThat(found.getSourceUpdatedAt()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(found.getLoadedAt()).isNotNull();
    }

    @Test
    void facility_busStopCount_null은_미확인으로_왕복한다() {
        Facility f = Facility.builder()
                .type(library).sourceType(SourceType.MANUAL).sourceKey("M-001")
                .district(District.GIHEUNG).name("보건소").address("용인시 기흥구 1")
                .latitude(new BigDecimal("37.27")).longitude(new BigDecimal("127.11")).build();
        Facility saved = facilityRepository.saveAndFlush(f);
        em.clear();

        assertThat(facilityRepository.findById(saved.getFacilityId()).orElseThrow().getBusStopCount()).isNull();
    }

    @Test
    void facility_enum은_문자열로_저장된다() {
        facilityRepository.saveAndFlush(newFacility("L-001"));

        assertThat(jdbc.queryForObject("select source_type from facility", String.class)).isEqualTo("LIB_STD");
        assertThat(jdbc.queryForObject("select access_type from facility", String.class)).isEqualTo("OPEN_HOURS");
    }

    @Test
    void facility_source_조합으로_조회한다() {
        facilityRepository.saveAndFlush(newFacility("L-001"));

        assertThat(facilityRepository.findBySourceTypeAndSourceKey(SourceType.LIB_STD, "L-001")).isPresent();
        assertThat(facilityRepository.findBySourceTypeAndSourceKey(SourceType.LIB_STD, "L-999")).isEmpty();
        assertThat(facilityRepository.findBySourceTypeAndSourceKey(SourceType.MANUAL, "L-001")).isEmpty();
    }

    @Test
    void facility_source_type_key_중복은_UNIQUE_위반() {
        facilityRepository.saveAndFlush(newFacility("L-001"));

        assertThatThrownBy(() -> facilityRepository.saveAndFlush(newFacility("L-001")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void facility_같은_source_key라도_source_type이_다르면_저장된다() {
        facilityRepository.saveAndFlush(newFacility("K-1"));
        Facility other = Facility.builder()
                .type(library).sourceType(SourceType.LIB_SMALL).sourceKey("K-1")
                .district(District.CHEOIN).name("작은도서관").address("용인시 처인구 1")
                .latitude(new BigDecimal("37.23")).longitude(new BigDecimal("127.20")).build();

        facilityRepository.saveAndFlush(other);

        assertThat(facilityRepository.count()).isEqualTo(2);
    }

    @Test
    void facility_updateFromSource는_키와_PK를_유지하고_나머지를_갱신한다() throws Exception {
        Facility saved = facilityRepository.saveAndFlush(newFacility("L-001"));
        Long id = saved.getFacilityId();
        var firstLoadedAt = saved.getLoadedAt();
        FacilityType sports = typeRepository.saveAndFlush(
                FacilityType.builder().name("생활체육시설").chipGroup(ChipGroup.SPORTS).build());
        Thread.sleep(20);

        saved.updateFromSource(sports, District.GIHEUNG, AccessType.RESERVATION,
                "새이름", "새주소 2", new BigDecimal("37.250000"), new BigDecimal("127.150000"),
                null, "매주 월요일", 5, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1));
        facilityRepository.saveAndFlush(saved);
        em.clear();

        Facility found = facilityRepository.findBySourceTypeAndSourceKey(SourceType.LIB_STD, "L-001").orElseThrow();
        assertThat(found.getFacilityId()).isEqualTo(id); // PK 유지
        assertThat(found.getSourceType()).isEqualTo(SourceType.LIB_STD); // 키 유지
        assertThat(found.getSourceKey()).isEqualTo("L-001");
        assertThat(found.getType().getName()).isEqualTo("생활체육시설");
        assertThat(found.getDistrict()).isEqualTo("기흥구");
        assertThat(found.getAccessType()).isEqualTo(AccessType.RESERVATION);
        assertThat(found.getName()).isEqualTo("새이름");
        assertThat(found.getAddress()).isEqualTo("새주소 2");
        assertThat(found.getLatitude()).isEqualByComparingTo("37.25");
        assertThat(found.getLongitude()).isEqualByComparingTo("127.15");
        assertThat(found.getPhone()).isNull(); // null로도 덮어쓴다
        assertThat(found.getClosedDayNote()).isEqualTo("매주 월요일");
        assertThat(found.getBusStopCount()).isEqualTo(5);
        assertThat(found.getBusCountedAt()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(found.getSourceUpdatedAt()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(found.getLoadedAt()).isAfter(firstLoadedAt);
        assertThat(facilityRepository.count()).isEqualTo(1);
    }

    @Test
    void facility_updateFromSource는_값이_같아도_loadedAt을_갱신한다() throws Exception {
        Facility saved = facilityRepository.saveAndFlush(newFacility("L-001"));
        var firstLoadedAt = saved.getLoadedAt();
        Thread.sleep(20);

        saved.updateFromSource(library, saved.getDistrict(), saved.getAccessType(),
                saved.getName(), saved.getAddress(), saved.getLatitude(), saved.getLongitude(),
                saved.getPhone(), saved.getClosedDayNote(), saved.getBusStopCount(),
                saved.getBusCountedAt(), saved.getSourceUpdatedAt());
        facilityRepository.saveAndFlush(saved);
        em.clear();

        assertThat(facilityRepository.findById(saved.getFacilityId()).orElseThrow().getLoadedAt())
                .isAfter(firstLoadedAt);
    }

    @Test
    void facility_updateFromSource는_허용되지_않은_district를_거부한다() {
        Facility saved = facilityRepository.saveAndFlush(newFacility("L-001"));

        assertThatThrownBy(() -> saved.updateFromSource(library, "분당구", null, "n", "a",
                saved.getLatitude(), saved.getLongitude(), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void district가_허용값이_아니면_거부한다() {
        assertThatThrownBy(() -> Facility.builder().district("분당구").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- facility_amenity ----------

    @Test
    void amenity_복합키_왕복과_같은_키_저장은_갱신() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        amenityRepository.saveAndFlush(new FacilityAmenity(fid, AmenityCode.PARKING, "주차 120면"));
        em.clear();

        FacilityAmenity found = amenityRepository.findById(new FacilityAmenityId(fid, AmenityCode.PARKING)).orElseThrow();
        assertThat(found.getDetail()).isEqualTo("주차 120면");
        assertThat(jdbc.queryForObject("select amenity_code from facility_amenity", String.class)).isEqualTo("PARKING");

        // 같은 키를 다시 save()하면 새 행이 아니라 갱신(merge)이다
        amenityRepository.saveAndFlush(new FacilityAmenity(fid, AmenityCode.PARKING, "주차 130면"));
        em.clear();
        assertThat(amenityRepository.count()).isEqualTo(1);
        assertThat(amenityRepository.findById(new FacilityAmenityId(fid, AmenityCode.PARKING)).orElseThrow().getDetail())
                .isEqualTo("주차 130면");

        // 다른 amenity_code는 별도 행
        amenityRepository.saveAndFlush(new FacilityAmenity(fid, AmenityCode.RENTAL_ROOM, null));
        assertThat(amenityRepository.count()).isEqualTo(2);
    }

    @Test
    void amenity_같은_키를_persist로_중복_삽입하면_오류() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        em.persist(new FacilityAmenity(fid, AmenityCode.PARKING, "a"));
        em.flush();
        em.clear();

        assertThatThrownBy(() -> {
            em.persist(new FacilityAmenity(fid, AmenityCode.PARKING, "b"));
            em.flush();
        }).isInstanceOfAny(jakarta.persistence.PersistenceException.class, DataIntegrityViolationException.class);
    }

    // ---------- facility_space ----------

    @Test
    void space_복합키_왕복과_같은_키_저장은_갱신() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        spaceRepository.saveAndFlush(new FacilitySpace(fid, SpaceType.GENERAL, "종합자료실"));
        spaceRepository.saveAndFlush(new FacilitySpace(fid, SpaceType.READING, "열람실"));
        em.clear();

        assertThat(spaceRepository.findById(new FacilitySpaceId(fid, SpaceType.READING)).orElseThrow().getSpaceName())
                .isEqualTo("열람실");
        assertThat(jdbc.queryForList("select space_type from facility_space order by space_type", String.class))
                .containsExactly("GENERAL", "READING");

        spaceRepository.saveAndFlush(new FacilitySpace(fid, SpaceType.GENERAL, "자료실(변경)"));
        em.clear();
        assertThat(spaceRepository.count()).isEqualTo(2);
        assertThat(spaceRepository.findById(new FacilitySpaceId(fid, SpaceType.GENERAL)).orElseThrow().getSpaceName())
                .isEqualTo("자료실(변경)");
    }

    // ---------- operating_hour ----------

    private OperatingHour hour(Long fid, SpaceType space, int day, Integer seq, DayState state, Integer open, Integer close) {
        return OperatingHour.builder()
                .facilityId(fid).spaceType(space).dayOfWeek((short) day)
                .intervalSeq(seq == null ? null : seq.shortValue())
                .dayState(state)
                .openMin(open == null ? null : open.shortValue())
                .closeMin(close == null ? null : close.shortValue())
                .build();
    }

    @Test
    void operatingHour_복합키_4컬럼_왕복_24시는_1440() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        hourRepository.saveAndFlush(hour(fid, SpaceType.GENERAL, 0, null, DayState.OPEN, 540, 1440));
        em.clear();

        OperatingHour found = hourRepository.findById(
                new OperatingHourId(fid, SpaceType.GENERAL, (short) 0, (short) 1)).orElseThrow();

        assertThat(found.getDayState()).isEqualTo(DayState.OPEN);
        assertThat(found.getOpenMin()).isEqualTo((short) 540);
        assertThat(found.getCloseMin()).isEqualTo((short) 1440);
        assertThat(found.getId().getIntervalSeq()).isEqualTo((short) 1); // 기본값 1
        assertThat(jdbc.queryForObject("select day_state from operating_hour", String.class)).isEqualTo("OPEN");
    }

    @Test
    void operatingHour_같은_요일에_구간이_여러개이면_interval_seq로_구분된다() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 1, 1, DayState.OPEN, 540, 720));
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 1, 2, DayState.OPEN, 780, 1080));
        // 다른 요일·다른 공간도 별도 행
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 2, 1, DayState.CLOSED, null, null));
        hourRepository.saveAndFlush(hour(fid, SpaceType.GENERAL, 1, 1, DayState.UNKNOWN, null, null));
        em.clear();

        assertThat(hourRepository.count()).isEqualTo(4);
    }

    @Test
    void operatingHour_같은_키_저장은_갱신() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 3, 1, DayState.OPEN, 540, 1080));
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 3, 1, DayState.OPEN, 600, 1200));
        em.clear();

        assertThat(hourRepository.count()).isEqualTo(1);
        OperatingHour found = hourRepository.findById(
                new OperatingHourId(fid, SpaceType.DEFAULT, (short) 3, (short) 1)).orElseThrow();
        assertThat(found.getOpenMin()).isEqualTo((short) 600);
        assertThat(found.getCloseMin()).isEqualTo((short) 1200);
    }

    @Test
    void operatingHour_CLOSED는_시간이_null로_왕복한다() {
        Long fid = facilityRepository.saveAndFlush(newFacility("L-001")).getFacilityId();
        hourRepository.saveAndFlush(hour(fid, SpaceType.DEFAULT, 0, 1, DayState.CLOSED, null, null));
        em.clear();

        OperatingHour found = hourRepository.findById(
                new OperatingHourId(fid, SpaceType.DEFAULT, (short) 0, (short) 1)).orElseThrow();
        assertThat(found.getDayState()).isEqualTo(DayState.CLOSED);
        assertThat(found.getOpenMin()).isNull();
        assertThat(found.getCloseMin()).isNull();
    }

    @Test
    void 복합키_equals_hashCode() {
        assertThat(new OperatingHourId(1L, SpaceType.GENERAL, (short) 0, (short) 1))
                .isEqualTo(new OperatingHourId(1L, SpaceType.GENERAL, (short) 0, (short) 1))
                .hasSameHashCodeAs(new OperatingHourId(1L, SpaceType.GENERAL, (short) 0, (short) 1))
                .isNotEqualTo(new OperatingHourId(1L, SpaceType.GENERAL, (short) 0, (short) 2));
        assertThat(new FacilitySpaceId(1L, SpaceType.READING))
                .isEqualTo(new FacilitySpaceId(1L, SpaceType.READING))
                .isNotEqualTo(new FacilitySpaceId(1L, SpaceType.GENERAL));
        assertThat(new FacilityAmenityId(1L, AmenityCode.PARKING))
                .isEqualTo(new FacilityAmenityId(1L, AmenityCode.PARKING))
                .isNotEqualTo(new FacilityAmenityId(2L, AmenityCode.PARKING));
    }
}
