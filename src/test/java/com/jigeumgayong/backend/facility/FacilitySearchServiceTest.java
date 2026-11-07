package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Item;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.FacilitySearchService;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.support.H2PostgresJpaTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@H2PostgresJpaTest
@Import(FacilitySearchService.class)
class FacilitySearchServiceTest {

    static final int MON = 0;

    @Autowired FacilitySearchService service;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;

    FacilityType library;
    FacilityType sports;
    int seq = 0;

    @BeforeEach
    void setUp() {
        library = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        sports = typeRepository.save(FacilityType.builder().name("생활체육시설").chipGroup("체육시설")
                .defaultImage("/img/sports.png").build());
    }

    private Facility facility(FacilityType type, String district, AccessType access, String name, String address) {
        return facilityRepository.save(Facility.builder()
                .type(type).sourceType(SourceType.MANUAL).sourceKey("k" + (seq++))
                .district(district).accessType(access).name(name).address(address)
                .latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
    }

    private void space(Facility f, SpaceType t, String label) {
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), t, label));
    }

    private void open(Facility f, SpaceType t, int day, int seqNo, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) day).intervalSeq((short) seqNo).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    private void state(Facility f, SpaceType t, int day, DayState s) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) day).intervalSeq((short) 1).dayState(s).build());
    }

    private FacilitySearchResponse search(String keyword, String district, List<Integer> types, String s, String e) {
        return service.search(keyword, district, types, MON, s, e);
    }

    @Test
    void general_closes_18_reading_22_only_reading_matches_19_to_21() {
        Facility f = facility(library, "수지구", AccessType.OPEN_HOURS, "수지도서관", "수지구 어딘가");
        space(f, SpaceType.GENERAL, "종합자료실");
        space(f, SpaceType.READING, "열람실");
        open(f, SpaceType.GENERAL, MON, 1, 540, 1080);
        open(f, SpaceType.READING, MON, 1, 540, 1320);

        FacilitySearchResponse r = search(null, null, null, "19:00", "21:00");

        assertThat(r.totalCount()).isEqualTo(1);
        assertThat(r.matchedSpaceCount()).isEqualTo(1);
        Item item = r.items().get(0);
        assertThat(item.availableSpaces()).hasSize(1);
        assertThat(item.availableSpaces().get(0).spaceType()).isEqualTo("READING");
        assertThat(item.availableSpaces().get(0).selectedSchedule().intervals().get(0).closeTime())
                .isEqualTo("22:00");
        assertThat(item.distanceMeters()).isNull();
        assertThat(item.facilityId()).isEqualTo(String.valueOf(f.getFacilityId()));
    }

    @Test
    void spaces_sorted_general_reading_default_and_union_of_spaces_not_allowed() {
        Facility f = facility(library, "수지구", AccessType.OPEN_HOURS, "A도서관", "주소");
        space(f, SpaceType.GENERAL, "종합자료실");
        space(f, SpaceType.READING, "열람실");
        open(f, SpaceType.GENERAL, MON, 1, 540, 1200);
        open(f, SpaceType.READING, MON, 1, 540, 1200);
        // 공간끼리 합쳐 덮는 경우: GENERAL 09~12, READING 12~18 → 어느 공간도 10~17을 덮지 못함
        Facility g = facility(library, "수지구", AccessType.OPEN_HOURS, "B도서관", "주소");
        space(g, SpaceType.GENERAL, "종합자료실");
        space(g, SpaceType.READING, "열람실");
        open(g, SpaceType.GENERAL, MON, 1, 540, 720);
        open(g, SpaceType.READING, MON, 1, 720, 1080);

        FacilitySearchResponse r = search(null, null, null, "10:00", "17:00");

        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).name()).isEqualTo("A도서관");
        assertThat(r.items().get(0).availableSpaces()).extracting("spaceType")
                .containsExactly("GENERAL", "READING");
    }

    @Test
    void adjacent_intervals_continue_but_lunch_gap_does_not() {
        Facility adj = facility(library, "수지구", AccessType.OPEN_HOURS, "이어짐", "주소");
        space(adj, SpaceType.DEFAULT, "시설 전체");
        open(adj, SpaceType.DEFAULT, MON, 1, 540, 720);
        open(adj, SpaceType.DEFAULT, MON, 2, 720, 1080);
        Facility gap = facility(library, "수지구", AccessType.OPEN_HOURS, "점심휴게", "주소");
        space(gap, SpaceType.DEFAULT, "시설 전체");
        open(gap, SpaceType.DEFAULT, MON, 1, 540, 720);
        open(gap, SpaceType.DEFAULT, MON, 2, 780, 1080);

        FacilitySearchResponse r = search(null, null, null, "10:00", "17:00");

        assertThat(r.items()).extracting(Item::name).containsExactly("이어짐");
        assertThat(r.items().get(0).availableSpaces().get(0).selectedSchedule().intervals()).hasSize(2);
        assertThat(r.unknownSpaceCount()).isZero();
    }

    @Test
    void closed_is_excluded_without_unknown_count_and_missing_rows_count_as_unknown() {
        Facility closed = facility(library, "수지구", AccessType.OPEN_HOURS, "휴관", "주소");
        space(closed, SpaceType.DEFAULT, "시설 전체");
        state(closed, SpaceType.DEFAULT, MON, DayState.CLOSED);
        Facility unknown = facility(library, "수지구", AccessType.OPEN_HOURS, "UNKNOWN상태", "주소");
        space(unknown, SpaceType.DEFAULT, "시설 전체");
        state(unknown, SpaceType.DEFAULT, MON, DayState.UNKNOWN);
        Facility noRows = facility(library, "수지구", AccessType.OPEN_HOURS, "행없음", "주소");
        space(noRows, SpaceType.DEFAULT, "시설 전체");
        // 매칭 시설의 UNKNOWN 공간도 센다
        Facility mixed = facility(library, "수지구", AccessType.OPEN_HOURS, "혼합", "주소");
        space(mixed, SpaceType.GENERAL, "종합자료실");
        space(mixed, SpaceType.READING, "열람실");
        open(mixed, SpaceType.GENERAL, MON, 1, 540, 1080);

        FacilitySearchResponse r = search(null, null, null, "10:00", "12:00");

        assertThat(r.items()).extracting(Item::name).containsExactly("혼합");
        assertThat(r.unknownSpaceCount()).isEqualTo(3); // UNKNOWN상태 + 행없음 + 혼합의 READING
    }

    @Test
    void always_open_is_included_without_hours_and_reservation_flag_is_set() {
        Facility court = facility(sports, "기흥구", AccessType.ALWAYS_OPEN, "공원 코트", "주소");
        space(court, SpaceType.DEFAULT, "시설 전체");
        Facility rsv = facility(sports, "기흥구", AccessType.RESERVATION, "예약 체육관", "주소");
        space(rsv, SpaceType.DEFAULT, "시설 전체");
        open(rsv, SpaceType.DEFAULT, MON, 1, 540, 1440);

        FacilitySearchResponse r = search(null, null, null, "20:00", "24:00");

        assertThat(r.items()).hasSize(2);
        var a = r.items().get(0).availableSpaces().get(0);
        assertThat(a.availability().basis()).isEqualTo("ACCESS_TYPE");
        assertThat(a.availability().requiresReservation()).isFalse();
        assertThat(a.selectedSchedule().intervals().get(0).openTime()).isEqualTo("00:00");
        assertThat(a.selectedSchedule().intervals().get(0).closeTime()).isEqualTo("24:00");
        var b = r.items().get(1).availableSpaces().get(0);
        assertThat(b.availability().requiresReservation()).isTrue();
        assertThat(b.availability().basis()).isEqualTo("WEEKLY_SCHEDULE");
        assertThat(r.unknownSpaceCount()).isZero();
        assertThat(r.items().get(0).imageUrl()).isEqualTo("/img/sports.png");
    }

    @Test
    void keyword_district_and_type_filters() {
        Facility a = facility(library, "수지구", AccessType.OPEN_HOURS, "Alpha Library", "용인시 수지구 100%길");
        Facility b = facility(sports, "기흥구", AccessType.OPEN_HOURS, "체육관", "용인시 기흥구 alpha로");
        Facility c = facility(library, "처인구", AccessType.OPEN_HOURS, "처인도서관", "처인구");
        for (Facility f : List.of(a, b, c)) {
            space(f, SpaceType.DEFAULT, "시설 전체");
            open(f, SpaceType.DEFAULT, MON, 1, 540, 1080);
        }

        // 이름 OR 주소, 대소문자 무시, 앞뒤 공백 trim
        assertThat(search("  ALPHA ", null, null, "10:00", "12:00").items())
                .extracting(Item::name).containsExactly("Alpha Library", "체육관");
        // 공백만이면 키워드 없음
        assertThat(search("   ", null, null, "10:00", "12:00").criteria().keyword()).isNull();
        assertThat(search("   ", null, null, "10:00", "12:00").totalCount()).isEqualTo(3);
        // % 는 글자 그대로
        assertThat(search("100%", null, null, "10:00", "12:00").items()).extracting(Item::name)
                .containsExactly("Alpha Library");
        assertThat(search("%", null, null, "10:00", "12:00").totalCount()).isEqualTo(1);
        // 구
        assertThat(search(null, "기흥구", null, "10:00", "12:00").items()).extracting(Item::name)
                .containsExactly("체육관");
        // 유형 OR
        List<Integer> both = List.of(library.getTypeId().intValue(), sports.getTypeId().intValue(),
                library.getTypeId().intValue());
        FacilitySearchResponse r = search(null, null, both, "10:00", "12:00");
        assertThat(r.totalCount()).isEqualTo(3);
        assertThat(r.criteria().facilityTypeIds()).hasSize(2); // 중복 제거
        assertThat(search(null, null, List.of(sports.getTypeId().intValue()), "10:00", "12:00").items())
                .extracting(Item::name).containsExactly("체육관");
    }

    @Test
    void no_result_is_empty_not_error_and_normalizes_criteria() {
        FacilitySearchResponse r = search(null, "수지구", null, "19:00", "21:00");

        assertThat(r.items()).isEmpty();
        assertThat(r.totalCount()).isZero();
        assertThat(r.matchedSpaceCount()).isZero();
        assertThat(r.unknownSpaceCount()).isZero();
        assertThat(r.criteria().facilityTypeIds()).isEmpty();
        assertThat(r.criteria().startTime()).isEqualTo("19:00");
    }

    @Test
    void unknown_type_id_is_invalid_request() {
        assertThatThrownBy(() -> search(null, null, List.of(9999), "10:00", "12:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void invalid_district_is_invalid_request_and_wins_over_bad_time() {
        assertThatThrownBy(() -> search(null, "서울", null, "bad", "12:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void always_open_without_default_space_row_uses_virtual_space() {
        facility(sports, "기흥구", AccessType.ALWAYS_OPEN, "공간행없는 코트", "주소");

        FacilitySearchResponse r = search(null, null, null, "06:00", "24:00");

        assertThat(r.items()).hasSize(1);
        var sp = r.items().get(0).availableSpaces().get(0);
        assertThat(sp.spaceType()).isEqualTo("DEFAULT");
        assertThat(sp.spaceLabel()).isEqualTo("시설 전체");
        assertThat(sp.availability().basis()).isEqualTo("ACCESS_TYPE");
        assertThat(r.matchedSpaceCount()).isEqualTo(1);
        assertThat(r.unknownSpaceCount()).isZero();
    }

    @Test
    void always_open_ignores_operating_hour_rows() {
        Facility court = facility(sports, "기흥구", AccessType.ALWAYS_OPEN, "행있는 코트", "주소");
        space(court, SpaceType.DEFAULT, "시설 전체");
        open(court, SpaceType.DEFAULT, MON, 1, 540, 600); // 09:00~10:00만 있어도 무시되어야 함

        FacilitySearchResponse r = search(null, null, null, "20:00", "24:00");

        assertThat(r.items()).hasSize(1);
        var sp = r.items().get(0).availableSpaces().get(0);
        assertThat(sp.availability().basis()).isEqualTo("ACCESS_TYPE");
        assertThat(sp.availability().requiresReservation()).isFalse();
        assertThat(sp.selectedSchedule().intervals()).hasSize(1);
        assertThat(sp.selectedSchedule().intervals().get(0).openTime()).isEqualTo("00:00");
        assertThat(sp.selectedSchedule().intervals().get(0).closeTime()).isEqualTo("24:00");
    }

    @Test
    void reservation_outside_hours_is_excluded() {
        Facility rsv = facility(sports, "기흥구", AccessType.RESERVATION, "예약 체육관", "주소");
        space(rsv, SpaceType.DEFAULT, "시설 전체");
        open(rsv, SpaceType.DEFAULT, MON, 1, 540, 1080);

        FacilitySearchResponse r = search(null, null, null, "19:00", "21:00");

        assertThat(r.items()).isEmpty();
        assertThat(r.totalCount()).isZero();
        assertThat(r.unknownSpaceCount()).isZero(); // 시간이 안 맞는 것은 UNKNOWN이 아니다
    }

    @Test
    void reservation_unknown_space_is_counted() {
        Facility noRows = facility(sports, "기흥구", AccessType.RESERVATION, "행없는 예약 체육관", "주소");
        space(noRows, SpaceType.DEFAULT, "시설 전체");
        Facility unknown = facility(sports, "기흥구", AccessType.RESERVATION, "UNKNOWN 예약 체육관", "주소");
        space(unknown, SpaceType.DEFAULT, "시설 전체");
        state(unknown, SpaceType.DEFAULT, MON, DayState.UNKNOWN);

        FacilitySearchResponse r = search(null, null, null, "10:00", "12:00");

        assertThat(r.items()).isEmpty();
        assertThat(r.unknownSpaceCount()).isEqualTo(2);
    }

    @Test
    void bad_time_is_invalid_time_range() {
        assertThatThrownBy(() -> search(null, null, null, "12:00", "10:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));
    }
}
