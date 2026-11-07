package com.jigeumgayong.backend.global.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record Meta(String requestId, OffsetDateTime generatedAt, List<Warning> warnings) {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	public Meta {
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	public static Meta of(String requestId, List<Warning> warnings) {
		return new Meta(requestId, OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS), warnings);
	}
}
