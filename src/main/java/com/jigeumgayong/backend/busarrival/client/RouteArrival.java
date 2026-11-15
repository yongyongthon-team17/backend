package com.jigeumgayong.backend.busarrival.client;

/**
 * 외부 응답에서 읽은 노선 1개의 도착 예정.
 *
 * @param routeName   노선번호(표시용)
 * @param minutesLeft 도착까지 남은 분. 응답에 없으면 null
 * @param stopsLeft   남은 정류장 수. 응답에 없으면 null
 */
public record RouteArrival(String routeName, Integer minutesLeft, Integer stopsLeft) {
}
