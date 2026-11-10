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
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.CandidateResult;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer;
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
@Import({ ImprovementCandidateFinder.class, SearchCriteriaNormalizer.class })
class ImprovementCandidateFinderTest {

    static final int TUE = 1;

    @Autowired ImprovementCandidateFinder finder;
    @Autowired SearchCriteriaNormalizer normalizer;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;

    FacilityType library;
    int seq = 0;

    @BeforeEach
    void setUp() {
        library = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    private Facility facility(AccessType access) {
        return facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(access).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
    }

    private void space(Facility f, SpaceType t, String label) {
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), t, label));
    }

    private void open(Facility f, SpaceType t, int seqNo, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) TUE).intervalSeq((short) seqNo).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    private void state(Facility f, SpaceType t, DayState s) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(s).build());
    }

    private CandidateResult find(Facility f, String start, String end) {
        return finder.findForFacility(f.getFacilityId(), normalizer.normalize(null, null, null, TUE, start, end), TUE);
    }

    @Test
    void 조기_마감은_후보이고_미운영_구간과_연장_가능_여부를_계산한다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.GENERAL, "종합자료실");
        open(f, SpaceType.GENERAL, 1, 540, 1080); // 09:00-18:00

        CandidateResult r = find(f, "19:00", "21:00");

        assertThat(r.candidates()).hasSize(1);
        Candidate c = r.candidates().get(0);
        assertThat(c.spaceType()).isEqualTo(SpaceType.GENERAL);
        assertThat(c.availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(c.availability().unavailableIntervals()).hasSize(1);
        assertThat(c.availability().unavailableIntervals().get(0).startTime()).isEqualTo("19:00");
        assertThat(c.availability().unavailableIntervals().get(0).endTime()).isEqualTo("21:00");
        assertThat(c.currentCloseMin()).isEqualTo(1080);
        assertThat(c.extendable()).isTrue();
        assertThat(c.requiresReservation()).isFalse();
        assertThat(c.selectedSchedule().intervals().get(0).closeTime()).isEqualTo("18:00");
    }

    @Test
    void 희망_시간을_모두_덮으면_후보가_아니다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, 1, 540, 1320);

        assertThat(find(f, "19:00", "21:00").candidates()).isEmpty();
    }

    @Test
    void 휴관은_요청_전체가_미운영_구간이고_종료_시각이_없다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        state(f, SpaceType.DEFAULT, DayState.CLOSED);

        Candidate c = find(f, "19:00", "21:00").candidates().get(0);

        assertThat(c.currentCloseMin()).isNull();
        assertThat(c.extendable()).isFalse();
        assertThat(c.availability().unavailableIntervals().get(0).startTime()).isEqualTo("19:00");
        assertThat(c.selectedSchedule().state()).isEqualTo("CLOSED");
    }

    @Test
    void UNKNOWN과_행_없음은_후보가_아니라_unknownSpaceCount다() {
        Facility a = facility(AccessType.OPEN_HOURS);
        space(a, SpaceType.DEFAULT, "시설 전체");
        state(a, SpaceType.DEFAULT, DayState.UNKNOWN);
        Facility b = facility(AccessType.OPEN_HOURS);
        space(b, SpaceType.DEFAULT, "시설 전체"); // 운영시간 행 없음

        for (Facility f : List.of(a, b)) {
            CandidateResult r = find(f, "19:00", "21:00");

            assertThat(r.candidates()).isEmpty();
            assertThat(r.unknownSpaceCount()).isEqualTo(1);
        }
    }

    @Test
    void ALWAYS_OPEN은_후보도_unknown_집계도_아니다() {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        space(f, SpaceType.DEFAULT, "시설 전체");

        CandidateResult r = find(f, "19:00", "21:00");

        assertThat(r.candidates()).isEmpty();
        assertThat(r.unknownSpaceCount()).isZero();
    }

    @Test
    void RESERVATION은_후보이고_예약_필요가_표시된다() {
        Facility f = facility(AccessType.RESERVATION);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, 1, 540, 1080);

        Candidate c = find(f, "19:00", "21:00").candidates().get(0);

        assertThat(c.requiresReservation()).isTrue();
        assertThat(c.availability().requiresReservation()).isTrue();
    }

    @Test
    void 종합자료실만_닫혀_있으면_열람실은_제외하고_종합자료실만_후보다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.GENERAL, "종합자료실");
        space(f, SpaceType.READING, "열람실");
        open(f, SpaceType.GENERAL, 1, 540, 1080);
        open(f, SpaceType.READING, 1, 540, 1320);

        List<Candidate> list = find(f, "19:00", "21:00").candidates();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).spaceType()).isEqualTo(SpaceType.GENERAL);
    }

    @Test
    void 점심_휴게로_생긴_빈틈은_종료_연장으로_해결되지_않는다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, 1, 540, 720);  // 09:00-12:00
        open(f, SpaceType.DEFAULT, 2, 780, 1080); // 13:00-18:00

        Candidate c = find(f, "11:00", "14:00").candidates().get(0);

        assertThat(c.availability().unavailableIntervals().get(0).startTime()).isEqualTo("12:00");
        assertThat(c.extendable()).isFalse();
    }

    @Test
    void 늦게_여는_공간도_종료_연장으로는_해결되지_않는다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        space(f, SpaceType.DEFAULT, "시설 전체");
        open(f, SpaceType.DEFAULT, 1, 600, 1080); // 10:00-18:00, 희망 08:00-20:00

        Candidate c = find(f, "08:00", "20:00").candidates().get(0);

        assertThat(c.availability().unavailableIntervals()).hasSize(2);
        assertThat(c.extendable()).isFalse();
    }

    @Test
    void 공간_순서는_GENERAL_READING_DEFAULT이고_다른_시설은_포함하지_않는다() {
        Facility other = facility(AccessType.OPEN_HOURS);
        space(other, SpaceType.DEFAULT, "시설 전체");
        open(other, SpaceType.DEFAULT, 1, 540, 1080);
        Facility f = facility(AccessType.OPEN_HOURS);
        // 저장 순서를 일부러 뒤집는다
        space(f, SpaceType.DEFAULT, "시설 전체");
        space(f, SpaceType.READING, "열람실");
        space(f, SpaceType.GENERAL, "종합자료실");
        open(f, SpaceType.DEFAULT, 1, 540, 1080);
        open(f, SpaceType.READING, 1, 540, 1080);
        open(f, SpaceType.GENERAL, 1, 540, 1080);

        List<Candidate> list = find(f, "19:00", "21:00").candidates();

        assertThat(list).extracting(c -> c.facility().getFacilityId() + ":" + c.spaceType())
                .containsExactly(f.getFacilityId() + ":GENERAL", f.getFacilityId() + ":READING",
                        f.getFacilityId() + ":DEFAULT");
    }

    @Test
    void 없는_시설은_FACILITY_NOT_FOUND() {
        assertThatThrownBy(() -> finder.findForFacility(999_999L,
                normalizer.normalize(null, null, null, TUE, "19:00", "21:00"), TUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void 존재하지_않는_유형은_400() {
        assertThatThrownBy(() -> normalizer.normalize(null, null, List.of(9999), TUE, "19:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
