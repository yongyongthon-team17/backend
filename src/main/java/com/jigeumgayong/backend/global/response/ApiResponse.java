package com.jigeumgayong.backend.global.response;

import com.jigeumgayong.backend.global.web.RequestIdHolder;
import java.util.List;
import java.util.Objects;

/** 07 3.4 공통 성공 응답 봉투. data/meta 모두 null 불가. */
public record ApiResponse<T>(T data, Meta meta) {

	public ApiResponse {
		Objects.requireNonNull(data, "data must not be null");
		Objects.requireNonNull(meta, "meta must not be null");
	}

	public static <T> ApiResponse<T> ok(T data) {
		return ok(data, List.of());
	}

	public static <T> ApiResponse<T> ok(T data, List<Warning> warnings) {
		return new ApiResponse<>(data, Meta.of(RequestIdHolder.currentOrNew(), warnings));
	}
}
