package com.jigeumgayong.backend.global.response;

/**
 * @param facilityId 특정 시설 경고면 BIGINT의 10진수 문자열, 전체 경고면 null (키는 항상 유지)
 */
public record Warning(String code, String message, String facilityId) {

	public static Warning of(WarningCode code, String message) {
		return new Warning(code.name(), message, null);
	}

	public static Warning of(WarningCode code, String message, String facilityId) {
		return new Warning(code.name(), message, facilityId);
	}
}
