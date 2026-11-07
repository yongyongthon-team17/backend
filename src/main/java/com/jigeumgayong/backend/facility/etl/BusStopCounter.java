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

    private final List<Stop> stops;

    BusStopCounter(List<Stop> stops) {
        this.stops = stops;
    }

    /** bus_stops.csv 행(stop_id, latitude, longitude)에서 만든다. 좌표가 잘못된 행과 중복 stop_id는 버린다. */
    static BusStopCounter fromRows(List<Map<String, String>> rows) {
        Set<String> seen = new HashSet<>();
        List<Stop> stops = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String id = s(r, "stop_id");
            Double lat = EtlUtil.toDouble(s(r, "latitude"));
            Double lng = EtlUtil.toDouble(s(r, "longitude"));
            if (id.isEmpty() || lat == null || lng == null || !seen.add(id)) {
                continue;
            }
            stops.add(new Stop(lat, lng));
        }
        return new BusStopCounter(stops);
    }

    boolean isEmpty() {
        return stops.isEmpty();
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
