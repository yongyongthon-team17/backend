package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.service.ImprovementObservations.Observation;
import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 관찰은 데이터에서 규칙대로 계산한 사실이다. 희망 시간과 무관하게 부족한 점을 모은다. */
class ImprovementObservationsTest {

    static final int TUE = 1;

    private static Facility facility(AccessType access) {
        return Facility.builder().district("수지구").accessType(access).name("시설").address("주소")
                .latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build();
    }

    private static DaySchedule open(int day, String from, String to) {
        return new DaySchedule(day, "OPEN", List.of(new TimeInterval(from, to)));
    }

    private static DaySchedule state(int day, String state) {
        return new DaySchedule(day, state, List.of());
    }

    /** 화요일 19~21시 희망 */
    private static Candidate space(Facility f, DaySchedule selected, Availability availability, Integer close) {
        return new Candidate(f, SpaceType.GENERAL, "종합자료실", selected, availability,
                f.getAccessType() == AccessType.RESERVATION, close, false, TUE, 1140, 1260);
    }

    private static Availability available() {
        return new Availability("AVAILABLE", "WEEKLY_SCHEDULE", false, List.of());
    }

    /** 7일 모두 같은 09~22시 */
    private static List<DaySchedule> uniformWeek() {
        List<DaySchedule> week = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            week.add(open(d, "09:00", "22:00"));
        }
        return week;
    }

    private static Accessibility stops(Integer count) {
        return count == null
                ? new Accessibility(Status.NO_DATA, 500, null, null, List.of())
                : new Accessibility(Status.AVAILABLE, 500, count, null, List.of());
    }

    private static List<String> types(List<Observation> list) {
        return list.stream().map(Observation::type).toList();
    }

    @Test
    void 희망_시간에_이용_가능하고_다른_문제도_없으면_관찰이_없다() {
        Facility f = facility(AccessType.OPEN_HOURS);

        List<Observation> list = ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "22:00"), available(), 1320)),
                Map.of(SpaceType.GENERAL, uniformWeek()), stops(20));

        assertThat(list).isEmpty();
    }

    @Test
    void 희망_시간에_비어_있으면_REQUEST_GAP이_첫_관찰이다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        Availability gap = new Availability("UNAVAILABLE", "WEEKLY_SCHEDULE", false, List.of(new Gap("19:00", "21:00")));

        List<Observation> list = ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "18:00"), gap, 1080)), Map.of(), stops(20));

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo("O1");
        assertThat(list.get(0).type()).isEqualTo("REQUEST_GAP");
        assertThat(list.get(0).spaceType()).isEqualTo("GENERAL");
        assertThat(list.get(0).detail()).contains("화요일").contains("19:00~21:00").contains("18:00");
    }

    @Test
    void 선택_요일_휴관과_운영시간_모름은_각각_관찰이_된다() {
        Facility f = facility(AccessType.OPEN_HOURS);

        List<Observation> closed = ImprovementObservations.build(
                List.of(space(f, state(TUE, "CLOSED"),
                        new Availability("UNAVAILABLE", "WEEKLY_SCHEDULE", false, List.of(new Gap("19:00", "21:00"))),
                        null)), Map.of(), stops(null));
        List<Observation> unknown = ImprovementObservations.build(
                List.of(space(f, state(TUE, "UNKNOWN"),
                        new Availability("UNKNOWN", "WEEKLY_SCHEDULE", false, null), null)), Map.of(), stops(null));

        assertThat(types(closed)).containsExactly("CLOSED_ON_REQUEST_DAY");
        assertThat(types(unknown)).containsExactly("HOURS_UNKNOWN");
    }

    @Test
    void 요일별_운영_길이가_두_시간_이상_차이나면_DAY_VARIANCE다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        List<DaySchedule> week = uniformWeek();
        week.set(5, open(5, "09:00", "17:00")); // 8시간 vs 13시간
        week.set(6, open(6, "09:00", "17:00"));

        List<Observation> list = ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "22:00"), available(), 1320)),
                Map.of(SpaceType.GENERAL, week), stops(20));

        assertThat(types(list)).containsExactly("DAY_VARIANCE");
        assertThat(list.get(0).detail()).contains("가장 짧은 요일 토").contains("09:00~17:00")
                .contains("가장 긴 요일 월").contains("09:00~22:00");
    }

    @Test
    void 차이가_두_시간_미만이면_DAY_VARIANCE가_아니다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        List<DaySchedule> week = uniformWeek();
        week.set(5, open(5, "09:00", "21:00")); // 1시간 차이

        assertThat(ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "22:00"), available(), 1320)),
                Map.of(SpaceType.GENERAL, week), stops(20))).isEmpty();
    }

    @Test
    void 선택_요일_외의_정기_휴관_요일을_REGULAR_CLOSED_DAYS로_모은다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        List<DaySchedule> week = uniformWeek();
        week.set(0, state(0, "CLOSED"));
        week.set(6, state(6, "CLOSED"));

        List<Observation> list = ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "22:00"), available(), 1320)),
                Map.of(SpaceType.GENERAL, week), stops(20));

        assertThat(types(list)).containsExactly("REGULAR_CLOSED_DAYS");
        assertThat(list.get(0).detail()).endsWith("정기 휴관: 월, 일");
    }

    @Test
    void 예약제와_정류장이_적은_경우는_시설_단위_관찰이고_정류장_숫자는_detail에_쓰지_않는다() {
        Facility f = facility(AccessType.RESERVATION);

        List<Observation> list = ImprovementObservations.build(
                List.of(space(f, open(TUE, "09:00", "22:00"), available(), 1320)),
                Map.of(SpaceType.GENERAL, uniformWeek()), stops(2));

        assertThat(types(list)).containsExactly("RESERVATION_REQUIRED", "FEW_BUS_STOPS");
        assertThat(list).extracting(Observation::spaceType).containsOnlyNulls();
        assertThat(list.get(1).detail()).doesNotContainPattern("\\d");
        assertThat(list).extracting(Observation::id).containsExactly("O1", "O2");
    }

    @Test
    void 정류장이_충분하거나_정보가_없으면_FEW_BUS_STOPS가_아니다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        Candidate c = space(f, open(TUE, "09:00", "22:00"), available(), 1320);

        assertThat(ImprovementObservations.build(List.of(c), Map.of(SpaceType.GENERAL, uniformWeek()), stops(5)))
                .isEmpty();
        assertThat(ImprovementObservations.build(List.of(c), Map.of(SpaceType.GENERAL, uniformWeek()), stops(null)))
                .isEmpty();
        assertThat(ImprovementObservations.build(List.of(c), Map.of(SpaceType.GENERAL, uniformWeek()), stops(4)))
                .hasSize(1); // 4개 이하
    }

    @Test
    void 상시_개방은_운영시간_관찰이_없다() {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        Candidate c = new Candidate(f, SpaceType.DEFAULT, "시설 전체", open(TUE, "00:00", "24:00"), available(),
                false, 1440, false, TUE, 1140, 1260);

        assertThat(ImprovementObservations.build(List.of(c), Map.of(), stops(20))).isEmpty();
    }

    @Test
    void 같은_입력이면_항상_같은_관찰과_id가_나온다() {
        Facility f = facility(AccessType.RESERVATION);
        List<DaySchedule> week = uniformWeek();
        week.set(6, state(6, "CLOSED"));
        Candidate c = space(f, open(TUE, "09:00", "22:00"), available(), 1320);

        assertThat(ImprovementObservations.build(List.of(c), Map.of(SpaceType.GENERAL, week), stops(1)))
                .isEqualTo(ImprovementObservations.build(List.of(c), Map.of(SpaceType.GENERAL, week), stops(1)));
    }
}
