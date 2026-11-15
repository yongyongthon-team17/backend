package com.jigeumgayong.backend.busarrival.dto;

import java.util.List;

/** A06 응답 data. 외부 API 실패·비활성도 200으로 주고 status로 구분한다. */
public record BusArrivalResponse(String facilityId, Status status, int radiusMeters, List<Stop> stops) {

    /** AVAILABLE=조회 성공(정류장 0곳 포함) / UNAVAILABLE=외부 API 실패·키 없음 / NOT_ENABLED=스위치 꺼짐 */
    public enum Status {
        AVAILABLE, UNAVAILABLE, NOT_ENABLED
    }

    /**
     * @param stopId         seed/bus_stops.csv의 정류장번호
     * @param distanceMeters 시설에서 직선거리(반올림)
     * @param arrivals       도착 예정 노선(남은 시간순, 최대 5개). 이 정류장 조회만 실패했으면 빈 목록
     */
    public record Stop(String stopId, String name, int distanceMeters, List<Arrival> arrivals) {
    }

    /**
     * @param minutesLeft 도착까지 남은 분(null=정보 없음)
     * @param stopsLeft   남은 정류장 수(null=정보 없음)
     */
    public record Arrival(String routeName, Integer minutesLeft, Integer stopsLeft) {
    }
}
