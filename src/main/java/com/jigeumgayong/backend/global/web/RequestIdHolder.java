package com.jigeumgayong.backend.global.web;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestIdHolder {

	public static final String MDC_KEY = "requestId";
	public static final String HEADER = "X-Request-Id";

	private RequestIdHolder() {
	}

	public static String generate() {
		return "req-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** 필터가 설정한 요청 ID. 없으면(필터 밖 호출) 새로 생성한다. */
	public static String currentOrNew() {
		String id = MDC.get(MDC_KEY);
		return id != null ? id : generate();
	}
}
