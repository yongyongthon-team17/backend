package com.jigeumgayong.backend.analysis.service;

/** LLM 응답 또는 저장된 payload가 검증을 통과하지 못함. 이런 결과는 저장하지도, HIT로 쓰지도 않는다. */
class InvalidPayloadException extends RuntimeException {

    InvalidPayloadException(String message) {
        super(message);
    }
}
