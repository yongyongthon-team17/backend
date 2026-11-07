package com.jigeumgayong.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 실제 설정(add-mappings=false, 필터 체인 포함)으로 존재하지 않는 URL이 404 봉투인지 검증. */
@SpringBootTest
class NotFoundHandlingIntegrationTest {

	@Autowired
	WebApplicationContext context;

	private MockMvc mvc() {
		return MockMvcBuilders.webAppContextSetup(context)
				.addFilters(new com.jigeumgayong.backend.global.web.RequestIdFilter())
				.build();
	}

	@Test
	void unknown_get_is_404_not_found_envelope() throws Exception {
		mvc().perform(get("/api/v1/none"))
				.andExpect(status().isNotFound())
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.error.message").value("요청한 경로를 찾을 수 없습니다."))
				.andExpect(jsonPath("$.error.details").isEmpty())
				.andExpect(jsonPath("$.error.requestId").isString());
	}

	@Test
	void error_request_id_equals_response_header() throws Exception {
		mvc().perform(get("/api/v1/none").header("X-Request-Id", "req-nf-1"))
				.andExpect(header().string("X-Request-Id", "req-nf-1"))
				.andExpect(jsonPath("$.error.requestId").value("req-nf-1"));
	}

	@Test
	void unknown_post_is_404_envelope() throws Exception {
		mvc().perform(post("/api/v1/none"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void root_and_favicon_are_404_envelope() throws Exception {
		mvc().perform(get("/")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
		mvc().perform(get("/favicon.ico")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
