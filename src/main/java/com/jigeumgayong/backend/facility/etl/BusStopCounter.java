package com.jigeumgayong.backend.facility.etl;

import static com.jigeumgayong.backend.facility.etl.EtlUtil.s;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시설 좌표 반경 500m 안의 버스정류장 수를 센다. 정류장은 stop_id로 중복 제거하고 거리는 직선(하버사인)이다.
 */
final class BusStopCounter {

    static final int RADIUS_METERS = 500;
    private static final double EARTH_RADIUS_M = 6_371_000;

    record Stop(double lat, double lng) {
    }

    /** 이름에 이 표기가 있는 정류장은 노선이 서지 않는 곳이라 세지 않는다(팀 결정). */
    static final String NON_STOP_MARK = "(미정차)";

    private final List<Stop> stops;
    private final int excludedNonStop;

    BusStopCounter(List<Stop> stops) {
        this(stops, 0);
    }

    private BusStopCounter(List<Stop> stops, int excludedNonStop) {
        this.stops = stops;
        this.excludedNonStop = excludedNonStop;
    }

    /**
     * bus_stops.csv 행(stop_id, name, latitude, longitude)에서 만든다.
     * 이름에 "(미정차)"가 있는 정류장, 좌표가 잘못된 행, 중복 stop_id는 버린다. name 열이 없으면 일반 정류장으로 본다.
     */
    static BusStopCounter fromRows(List<Map<String, String>> rows) {
        Set<String> seen = new HashSet<>();
        List<Stop> stops = new ArrayList<>();
        int excluded = 0;
        for (Map<String, String> r : rows) {
            // 제외한 행의 stop_id가 다른 행의 중복 판정에 영향을 주지 않게, 중복 검사보다 먼저 거른다
            if (s(r, "name").contains(NON_STOP_MARK)) {
                excluded++;
                continue;
            }
            String id = s(r, "stop_id");
            Double lat = EtlUtil.toDouble(s(r, "latitude"));
            Double lng = EtlUtil.toDouble(s(r, "longitude"));
            if (id.isEmpty() || lat == null || lng == null || !seen.add(id)) {
                continue;
            }
            stops.add(new Stop(lat, lng));
        }
        return new BusStopCounter(stops, excluded);
    }

    boolean isEmpty() {
        return stops.isEmpty();
    }

    /** "(미정차)"라서 세지 않은 정류장 수(적재 로그용) */
    int excludedNonStop() {
        return excludedNonStop;
    }

    /** 반경 500m 이내 정류장 수. 0은 '확인 결과 0곳'이다. 정류장 자료가 비어 있으면 호출 쪽이 계산하지 않는다 (R14). */
    int countWithin(double lat, double lng) {
        int count = 0;
        for (Stop stop : stops) {
            if (distanceMeters(lat, lng, stop.lat(), stop.lng()) <= RADIUS_METERS) {
                count++;
            }
        }
        return count;
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
