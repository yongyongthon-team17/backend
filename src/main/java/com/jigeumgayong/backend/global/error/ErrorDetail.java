package com.jigeumgayong.backend.global.error;

public record ErrorDetail(String field, String reason) {

	// 임시 reason 코드 (07에 목록 없음; 확정 시 교체)
	public static final String REQUIRED = "REQUIRED";
	public static final String INVALID_FORMAT = "INVALID_FORMAT";
	public static final String INVALID_VALUE = "INVALID_VALUE";
	public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
}
