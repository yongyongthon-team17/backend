package com.jigeumgayong.backend.global.error;

import java.util.List;

public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final List<ErrorDetail> details;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), List.of());
	}

	public BusinessException(ErrorCode errorCode, String message) {
		this(errorCode, message, List.of());
	}

	public BusinessException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
		super(message);
		this.errorCode = errorCode;
		this.details = details == null ? List.of() : List.copyOf(details);
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public List<ErrorDetail> getDetails() {
		return details;
	}
}
