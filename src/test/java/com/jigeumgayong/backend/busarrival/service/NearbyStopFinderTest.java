package com.jigeumgayong.backend.busarrival.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.busarrival.service.NearbyStopFinder.CsvStop;
import com.jigeumgayong.backend.busarrival.service.NearbyStopFinder.NearbyStop;
import java.util.List;
import org.junit.jupiter.api.Test;

class NearbyStopFinderTest {

    // 위도 0.001도 ≈ 111m
    final NearbyStopFinder finder = new NearbyStopFinder(List.of(
            new CsvStop("S3", "세번째", 37.003, 127.0),
            new CsvStop("S1", "첫번째", 37.001, 127.0),
            new CsvStop("N1", "미정차(미정차)", 37.0, 127.0),
            new CsvStop("S2", "두번째", 37.002, 127.0),
            new CsvStop("S1", "중복", 37.0001, 127.0),
            new CsvStop("S4", "네번째", 37.004, 127.0),
            new CsvStop("FAR", "멀리", 37.01, 127.0)));

    @Test
    void 미정차_중복_반경밖을_빼고_가까운순으로_최대3곳() {
        List<NearbyStop> result = finder.find(37.0, 127.0, 500, 3);

        assertThat(result).extracting(NearbyStop::stopId).containsExactly("S1", "S2", "S3");
        assertThat(result.get(0).distanceMeters()).isBetween(105, 117);
    }

    @Test
    void seed_CSV를_읽어_실제_정류장을_찾는다() {
        List<NearbyStop> result = NearbyStopFinder.fromSeed().find(37.2782167, 127.1541833, 500, 3);

        assertThat(result).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(result.get(0).stopId()).isEqualTo("GGB228001782");
        assertThat(result).allSatisfy(s -> assertThat(s.name()).doesNotContain("(미정차)"));
    }
}
