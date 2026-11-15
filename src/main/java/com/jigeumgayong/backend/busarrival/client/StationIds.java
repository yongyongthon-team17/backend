package com.jigeumgayong.backend.busarrival.client;

/** 정류장 ID 변환은 여기서만 한다. */
public final class StationIds {

    /** 국토교통부 정류장번호의 경기도 접두어 */
    static final String GYEONGGI_PREFIX = "GGB";

    private StationIds() {
    }

    /**
     * CSV stop_id(GGB228001782) → 경기도 도착정보 API stationId(228001782).
     * [미검증] 접두어를 뗀 값이 stationId와 같다고 본다. 실제 응답 샘플로 확인 후 이 메서드만 고친다.
     */
    public static String toArrivalStationId(String stopId) {
        return stopId.startsWith(GYEONGGI_PREFIX) ? stopId.substring(GYEONGGI_PREFIX.length()) : stopId;
    }
}
