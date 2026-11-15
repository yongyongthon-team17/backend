package com.jigeumgayong.backend.busarrival.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.ClassPathResource;

/**
 * seed/bus_stops.csv에서 시설 주변 정류장을 고른다. ETL(BusStopCounter)과 같은 규칙:
 * 이름에 "(미정차)"가 있는 정류장·좌표 오류·중복 stop_id는 버리고, 거리는 하버사인 직선거리.
 */
class NearbyStopFinder {

    static final String NON_STOP_MARK = "(미정차)";
    private static final double EARTH_RADIUS_M = 6_371_000;

    record CsvStop(String stopId, String name, double lat, double lng) {
    }

    record NearbyStop(String stopId, String name, int distanceMeters) {
    }

    private final List<CsvStop> stops;

    NearbyStopFinder(List<CsvStop> rows) {
        Set<String> seen = new HashSet<>();
        List<CsvStop> kept = new ArrayList<>();
        for (CsvStop s : rows) {
            // 제외 행이 중복 판정에 영향을 주지 않게 미정차를 먼저 거른다(ETL과 같은 순서)
            if (s.name().contains(NON_STOP_MARK) || s.stopId().isEmpty() || !seen.add(s.stopId())) {
                continue;
            }
            kept.add(s);
        }
        this.stops = List.copyOf(kept);
    }

    /** classpath:seed/bus_stops.csv(UTF-8)를 읽는다. 파일이 없으면 기동 실패로 바로 알린다. */
    static NearbyStopFinder fromSeed() {
        List<CsvStop> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        try (Reader reader = new InputStreamReader(
                new ClassPathResource("seed/bus_stops.csv").getInputStream(), StandardCharsets.UTF_8)) {
            for (CSVRecord r : format.parse(reader)) {
                // 헤더에 BOM이 붙을 수 있어 열 위치로 읽는다: stop_id, name, latitude, longitude
                try {
                    rows.add(new CsvStop(r.get(0).trim(), r.get(1).trim(),
                            Double.parseDouble(r.get(2)), Double.parseDouble(r.get(3))));
                } catch (RuntimeException badRow) {
                    // 좌표 오류·열 부족 행은 건너뛴다
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("seed/bus_stops.csv를 읽을 수 없음", e);
        }
        return new NearbyStopFinder(rows);
    }

    /** 반경 안 정류장을 가까운 순으로 최대 limit곳 */
    List<NearbyStop> find(double lat, double lng, int radiusMeters, int limit) {
        return stops.stream()
                .map(s -> new NearbyStop(s.stopId(), s.name(),
                        (int) Math.round(distanceMeters(lat, lng, s.lat(), s.lng()))))
                .filter(s -> s.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingInt(NearbyStop::distanceMeters))
                .limit(limit)
                .toList();
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
