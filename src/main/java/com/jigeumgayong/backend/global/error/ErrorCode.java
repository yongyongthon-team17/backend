package com.jigeumgayong.backend.global.error;

import org.springframework.http.HttpStatus;

/** 07 3.5 HTTP/code 표. 401/403은 인증 정책 확정 후 추가. */
public enum ErrorCode {
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시간 범위가 올바르지 않습니다."),
	FACILITY_NOT_FOUND(HttpStatus.NOT_FOUND, "시설을 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
	RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
	DATA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "필요한 데이터를 사용할 수 없습니다."),
	FEATURE_NOT_ENABLED(HttpStatus.SERVICE_UNAVAILABLE, "비활성화된 기능입니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
