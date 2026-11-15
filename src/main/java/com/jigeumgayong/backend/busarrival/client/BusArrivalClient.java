package com.jigeumgayong.backend.busarrival.client;

import java.util.List;

/** 정류장 1곳의 도착 예정 노선 조회. 테스트에서는 가짜 구현으로 바꾼다. */
public interface BusArrivalClient {

    /**
     * @param stopId seed/bus_stops.csv의 stop_id(예: GGB228001782). 외부 API용 변환은 구현 안에서 한다
     * @return 도착 예정 목록(정렬·개수 제한 전). 도착 예정이 없으면 빈 목록
     * @throws BusApiException 호출·응답 해석 실패
     */
    List<RouteArrival> fetchArrivals(String stopId);
}
