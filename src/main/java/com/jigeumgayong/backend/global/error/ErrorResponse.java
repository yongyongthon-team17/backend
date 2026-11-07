package com.jigeumgayong.backend.global.error;

import java.util.List;

/** 07 3.5 공통 오류 응답: {"error": {...}} */
public record ErrorResponse(Body error) {

	public record Body(String code, String message, List<ErrorDetail> details, String requestId) {

		public Body {
			details = details == null ? List.of() : List.copyOf(details);
		}
	}

	public static ErrorResponse of(ErrorCode code, String message, List<ErrorDetail> details, String requestId) {
		return new ErrorResponse(new Body(code.name(), message, details, requestId));
	}
}
