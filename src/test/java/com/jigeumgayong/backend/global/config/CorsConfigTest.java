package com.jigeumgayong.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CorsConfigTest {

	@Test
	void parses_comma_separated_origins_with_spaces_and_blanks() {
		assertThat(CorsConfig.parseOrigins(" http://localhost:5173 , ,http://localhost:3000,"))
				.containsExactly("http://localhost:5173", "http://localhost:3000");
	}

	@Test
	void empty_or_null_means_no_origins() {
		assertThat(CorsConfig.parseOrigins("")).isEmpty();
		assertThat(CorsConfig.parseOrigins("  ")).isEmpty();
		assertThat(CorsConfig.parseOrigins(null)).isEmpty();
	}
}
