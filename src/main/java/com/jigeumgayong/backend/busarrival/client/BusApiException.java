package com.jigeumgayong.backend.busarrival.client;

/**
 * 버스 API 호출 실패. 메시지에 요청 URI(서비스 키 포함)를 넣지 않고, 원인 예외도 붙이지 않는다
 * (RestClient 예외 메시지에는 키가 든 URI가 들어 있다).
 */
public class BusApiException extends RuntimeException {

    public BusApiException(String message) {
        super(message);
    }
}
