package com.jigeumgayong.backend.global.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** prod에서 CORS_ALLOWED_ORIGINS가 비어 있는 경우: 교차 출처 허용 헤더를 내보내지 않는다. */
@SpringBootTest(properties = "app.cors.allowed-origins=")
@AutoConfigureMockMvc
class CorsDisabledIntegrationTest {

	static final String ACAO = "Access-Control-Allow-Origin";

	@Autowired
	MockMvc mvc;

	@Test
	void get_has_no_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/facility-search-options").header("Origin", "http://localhost:5173"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(ACAO));
	}

	@Test
	void preflight_has_no_cors_headers() throws Exception {
		mvc.perform(options("/api/v1/facility-search-options")
						.header("Origin", "http://localhost:5173")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(header().doesNotExist(ACAO))
				.andExpect(header().doesNotExist("Access-Control-Allow-Methods"));
	}
}
