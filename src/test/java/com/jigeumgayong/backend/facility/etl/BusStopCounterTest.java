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
}
