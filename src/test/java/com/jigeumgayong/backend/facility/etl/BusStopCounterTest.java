package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 위도 0.0009도 ≈ 100m (지구 반지름 6,371km 기준). 같은 경도 위에 정류장을 둬서 거리를 쉽게 만든다. */
class BusStopCounterTest {

    static final double LAT = 37.30;
    static final double LNG = 127.10;

    private static Map<String, String> row(String id, double lat, double lng) {
        return Map.of("stop_id", id, "latitude", String.valueOf(lat), "longitude", String.valueOf(lng));
    }

    private static BusStopCounter counterWithStopsAtNorth(double... degrees) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < degrees.length; i++) {
            rows.add(row("S" + i, LAT + degrees[i], LNG));
        }
        return BusStopCounter.fromRows(rows);
    }

    @Test
    void 반경_500m_이내_정류장만_센다() {
        // 약 100m, 200m, 499m(포함), 501m(제외)
        BusStopCounter counter = counterWithStopsAtNorth(0.0009, 0.0018, 0.00449, 0.00451);

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(3);
    }

    @Test
    void 정류장이_500m_밖에만_있으면_0이다() {
        assertThat(counterWithStopsAtNorth(0.0090).countWithin(LAT, LNG)).isZero(); // 약 1,001m
    }

    @Test
    void 같은_stop_id는_한_번만_센다() {
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                row("A", LAT + 0.0009, LNG), row("A", LAT + 0.0009, LNG), row("B", LAT + 0.0018, LNG)));

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(2);
    }

    @Test
    void 좌표나_id가_잘못된_행은_버린다() {
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                Map.of("stop_id", "", "latitude", "37.3", "longitude", "127.1"),
                Map.of("stop_id", "X", "latitude", "abc", "longitude", "127.1"),
                row("OK", LAT + 0.0009, LNG)));

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(1);
    }

    @Test
    void 정류장_자료가_비어_있으면_isEmpty이다() {
        assertThat(BusStopCounter.fromRows(List.of()).isEmpty()).isTrue();
    }

    private static Map<String, String> named(String id, String name, double lat, double lng) {
        return Map.of("stop_id", id, "name", name, "latitude", String.valueOf(lat), "longitude", String.valueOf(lng));
    }

    @Test
    void 이름에_미정차가_있는_정류장은_세지_않는다() {
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                named("A", "버드실사거리", LAT + 0.0009, LNG),
                named("B", "백암터미널(미정차)", LAT + 0.0018, LNG),
                named("C", "구성중학교", LAT + 0.0027, LNG)));

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(2);
        assertThat(counter.excludedNonStop()).isEqualTo(1);
    }

    @Test
    void 이름이_없거나_다른_표기는_일반_정류장으로_센다() {
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                row("A", LAT + 0.0009, LNG), // name 열 없음
                named("B", "정차대", LAT + 0.0018, LNG), // '정차'만 들어간 이름
                named("C", "(임시)마을회관", LAT + 0.0027, LNG)));

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(3);
        assertThat(counter.excludedNonStop()).isZero();
    }

    @Test
    void 제외한_행의_stop_id는_다른_행의_중복_판정에_영향을_주지_않는다() {
        // 같은 id의 미정차 행이 먼저 와도, 뒤에 오는 일반 정류장은 센다
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                named("A", "가곡마을회관(미정차)", LAT + 0.0009, LNG),
                named("A", "가곡마을회관", LAT + 0.0009, LNG)));

        assertThat(counter.countWithin(LAT, LNG)).isEqualTo(1);
        assertThat(counter.excludedNonStop()).isEqualTo(1);
    }

    @Test
    void 미정차만_있으면_정류장_자료가_비어_있는_것으로_본다() {
        BusStopCounter counter = BusStopCounter.fromRows(List.of(
                named("A", "내기삼거리(미정차)", LAT + 0.0009, LNG)));

        assertThat(counter.isEmpty()).isTrue();
        assertThat(counter.excludedNonStop()).isEqualTo(1);
    }
}
