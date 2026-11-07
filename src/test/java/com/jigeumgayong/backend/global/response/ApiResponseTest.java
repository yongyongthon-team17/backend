package com.jigeumgayong.backend.global.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

	@Test
	void ok_defaults_to_empty_warnings_and_seoul_offset() {
		ApiResponse<String> r = ApiResponse.ok("d");

		assertThat(r.data()).isEqualTo("d");
		assertThat(r.meta().warnings()).isEmpty();
		assertThat(r.meta().requestId()).startsWith("req-");
		assertThat(r.meta().generatedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
	}

	@Test
	void warning_keeps_facilityId_null_for_global_warning() {
		Warning w = Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY, "msg");

		assertThat(w.code()).isEqualTo("WEEKLY_SCHEDULE_ONLY");
		assertThat(w.facilityId()).isNull();
		assertThat(ApiResponse.ok("d", List.of(w)).meta().warnings()).hasSize(1);
	}

	@Test
	void data_must_not_be_null() {
		assertThatThrownBy(() -> ApiResponse.ok(null)).isInstanceOf(NullPointerException.class);
	}
}
