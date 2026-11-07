package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.SpaceDetail;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.FacilityDetailService;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.support.H2PostgresJpaTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@H2PostgresJpaTest
@Import(FacilityDetailService.class)
class FacilityDetailServiceTest {

    static final int TUE = 1;

    @Autowired FacilityDetailService service;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    FacilityType library;
    int seq = 0;

    @BeforeEach
    void setUp() {
        library = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    private Facility facility(AccessType access) {
        return facilityRepository.save(Facility.builder()
                .type(library).sourceType(SourceType.MANUAL).sourceKey("k" + (seq++))
                .district("수지구").accessType(access).name("시설" + seq).address("용인시 수지구")
                .latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1"))
                .phone("031-000-0000").closedDayNote("둘째 월요일 휴관")
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

    private void state(Facility f, SpaceType t, int day, DayState s) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) day).intervalSeq((short) 1).dayState(s).build());
    }

    private FacilityDetailResponse detail(Facility f, String s, String e) {
        return service.detail(String.valueOf(f.getFacilityId()), TUE, s, e);
    }

    // ---------- ALWAYS_OPEN / RESERVATION ----------

    @Test
    void always_open은_행_없이_DEFAULT_하나와_ACCESS_TYPE() {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        space(f, SpaceType.DEFAULT, "시설 전체");

        FacilityDetailResponse r = detail(f, "19:00", "21:00");

        assertThat(r.facility().spaces()).hasSize(1);
        SpaceDetail s = r.facility().spaces().get(0);
        assertThat(s.spaceType()).isEqualTo("DEFAULT");
        assertThat(s.availability().status()).isEqualTo("AVAILABLE");
        assertThat(s.availability().basis()).isEqualTo("ACCESS_TYPE");
        assertThat(s.availability().requiresReservation()).isFalse();
        assertThat(s.weeklyHours()).hasSize(7).allSatisfy(d -> {
            assertThat(d.state()).isEqualTo("OPEN");
            assertThat(d.intervals()).hasSize(1);
            assertThat(d.intervals().get(0).openTime()).isEqualTo("00:00");
            assertThat(d.intervals().get(0).closeTime()).isEqualTo("24:00");
        });
        assertThat(s.selectedSchedule()).isEqualTo(s.weeklyHours().get(TUE));
    }

    @Test
    void reservation은_모든_공간에_requiresReservation() {
        Facility f = facility(AccessType.RESERVATION);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, TUE, 1, 540, 1320);

        SpaceDetail ok = detail(f, "19:00", "21:00").facility().spaces().get(0);
        assertThat(ok.availability().status()).isEqualTo("AVAILABLE");
        assertThat(ok.availability().requiresReservation()).isTrue();

        SpaceDetail unavailable = detail(f, "20:00", "23:00").facility().spaces().get(0);
        assertThat(unavailable.availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.availability().requiresReservation()).isTrue();
    }

    // ---------- 판정 ----------

    @Test
    void 점심_휴게는_구간별로_판정한다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, TUE, 1, 540, 720);   // 09:00-12:00
        open(f, SpaceType.DEFAULT, TUE, 2, 780, 1080);  // 13:00-18:00

        SpaceDetail s = detail(f, "10:00", "11:00").facility().spaces().get(0);
        assertThat(s.availability().status()).isEqualTo("AVAILABLE");
        assertThat(s.selectedSchedule().intervals()).hasSize(2);
        assertThat(s.selectedSchedule().intervals().get(1).openTime()).isEqualTo("13:00");

        SpaceDetail lunch = detail(f, "11:00", "14:00").facility().spaces().get(0);
        assertThat(lunch.availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(lunch.availability().unavailableIntervals()).containsExactly(new Gap("12:00", "13:00"));
    }

    @Test
    void 공간별로_다르게_판정하고_GENERAL_READING_순서() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.READING, "열람실");
        space(f, SpaceType.GENERAL, "종합자료실");
        open(f, SpaceType.GENERAL, TUE, 1, 540, 1080);
        open(f, SpaceType.READING, TUE, 1, 540, 1320);

        var spaces = detail(f, "19:00", "21:00").facility().spaces();

        assertThat(spaces).extracting(SpaceDetail::spaceType).containsExactly("GENERAL", "READING");
        assertThat(spaces.get(0).availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(spaces.get(0).availability().unavailableIntervals()).containsExactly(new Gap("19:00", "21:00"));
        assertThat(spaces.get(1).availability().status()).isEqualTo("AVAILABLE");
        assertThat(spaces.get(1).availability().unavailableIntervals()).isEmpty();
    }

    @Test
    void 행이_없으면_UNKNOWN이고_휴관으로_바꾸지_않는다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");

        SpaceDetail s = detail(f, "19:00", "21:00").facility().spaces().get(0);

        assertThat(s.availability().status()).isEqualTo("UNKNOWN");
        assertThat(s.availability().unavailableIntervals()).isNull();
        assertThat(s.selectedSchedule().state()).isEqualTo("UNKNOWN");
        assertThat(s.selectedSchedule().intervals()).isEmpty();
        assertThat(s.weeklyHours()).hasSize(7).allSatisfy(d -> assertThat(d.state()).isEqualTo("UNKNOWN"));
    }

    @Test
    void CLOSED_요일은_요청_전체가_미운영이고_누락_요일은_UNKNOWN() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        state(f, SpaceType.DEFAULT, TUE, DayState.CLOSED);
        open(f, SpaceType.DEFAULT, 0, 1, 540, 1080);

        SpaceDetail s = detail(f, "19:00", "21:00").facility().spaces().get(0);

        assertThat(s.availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(s.availability().unavailableIntervals()).containsExactly(new Gap("19:00", "21:00"));
        assertThat(s.selectedSchedule().state()).isEqualTo("CLOSED");
        assertThat(s.weeklyHours()).extracting(d -> d.dayOfWeek()).containsExactly(0, 1, 2, 3, 4, 5, 6);
        assertThat(s.weeklyHours().get(0).state()).isEqualTo("OPEN");
        assertThat(s.weeklyHours().get(2).state()).isEqualTo("UNKNOWN");
    }

    @Test
    void 자정_마감_24시_경계() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, TUE, 1, 540, 1440);

        SpaceDetail s = detail(f, "22:00", "24:00").facility().spaces().get(0);

        assertThat(s.availability().status()).isEqualTo("AVAILABLE");
        assertThat(s.selectedSchedule().intervals().get(0).closeTime()).isEqualTo("24:00");
    }

    // ---------- 시설 필드 / amenities ----------

    @Test
    void 시설_필드와_dataUpdatedAt() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");

        var d = detail(f, "19:00", "21:00").facility();

        assertThat(d.facilityId()).isEqualTo(String.valueOf(f.getFacilityId()));
        assertThat(d.typeName()).isEqualTo("도서관");
        assertThat(d.phone()).isEqualTo("031-000-0000");
        assertThat(d.closedDayNote()).isEqualTo("둘째 월요일 휴관");
        assertThat(d.dataUpdatedAt()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void amenities는_열람실_태그_detail_코드라벨_순서로_조합한다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.GENERAL, "종합자료실");
        space(f, SpaceType.READING, "열람실");
        amenityRepository.save(new FacilityAmenity(f.getFacilityId(), AmenityCode.RENTAL_ROOM, null));
        amenityRepository.save(new FacilityAmenity(f.getFacilityId(), AmenityCode.PARKING, "주차 120면"));

        assertThat(detail(f, "19:00", "21:00").facility().amenities())
                .containsExactly("열람실", "주차 120면", "대관 가능");
    }

    @Test
    void amenities가_없으면_null이_아니라_빈_배열() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");

        assertThat(detail(f, "19:00", "21:00").facility().amenities()).isNotNull().isEmpty();
    }

    // ---------- 오류 ----------

    @Test
    void 시설이_없으면_FACILITY_NOT_FOUND() {
        assertThatThrownBy(() -> service.detail("999999", TUE, "19:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void 잘못된_입력은_400() {
        for (String bad : new String[] { "abc", "0", "-1", "01", "1.5", "", "99999999999999999999" }) {
            assertThatThrownBy(() -> service.detail(bad, TUE, "19:00", "21:00"))
                    .as("facilityId=%s", bad)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThatThrownBy(() -> service.detail("1", 7, "19:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.detail("1", TUE, "21:00", "19:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));
        assertThatThrownBy(() -> service.detail("1", TUE, "9:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));
    }
}
