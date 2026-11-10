package com.jigeumgayong.backend.global.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 필터 체인으로 CORS 확인. 허용 출처는 local 프로파일 기본값(5173, 3000).
 * 정상 응답뿐 아니라 오류 봉투(400·404·405·500·503)에도 헤더가 붙어야 FE가 본문을 읽을 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

	static final String ALLOWED = "http://localhost:5173";
	static final String ALLOWED_2 = "http://localhost:3000";
	static final String DENIED = "http://evil.example";
	static final String ACAO = "Access-Control-Allow-Origin";

	@Autowired
	MockMvc mvc;

	@Autowired
	FacilityTypeRepository typeRepository;

	@Autowired
	FacilityRepository facilityRepository;

	/** 500·503 오류 응답 확인용 테스트 전용 엔드포인트 */
	@TestConfiguration
	static class ErrorEndpoints {
		@Bean
		FailingController failingController() {
			return new FailingController();
		}
	}

	@RestController
	static class FailingController {
		@GetMapping("/api/v1/test-cors/500")
		String internal() {
			throw new IllegalStateException("boom");
		}

		@GetMapping("/api/v1/test-cors/503")
		String unavailable() {
			throw new DataAccessResourceFailureException("db down");
		}
	}

	// --- preflight ---

	@Test
	void preflight_from_allowed_origin() throws Exception {
		mvc.perform(options("/api/v1/facility-search-options")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,OPTIONS"))
				.andExpect(header().string("Access-Control-Max-Age", "3600"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
	}

	@Test
	void preflight_second_allowed_origin() throws Exception {
		mvc.perform(options("/api/v1/facility-search-options")
						.header("Origin", ALLOWED_2)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(ACAO, ALLOWED_2));
	}

	@Test
	void preflight_post_with_content_type_and_request_id() throws Exception {
		mvc.perform(options("/api/v1/improvement-analyses")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "content-type,x-request-id"))
				.andExpect(status().isOk())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Headers", containsString("content-type")))
				.andExpect(header().string("Access-Control-Allow-Headers", containsString("x-request-id")));
	}

	@Test
	void preflight_allows_client_id_header_for_ai_rate_limit() throws Exception {
		mvc.perform(options("/api/v1/improvement-analyses")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "content-type,x-client-id"))
				.andExpect(status().isOk())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Headers", containsString("x-client-id")));
	}

	@Test
	void preflight_with_unlisted_header_is_rejected() throws Exception {
		mvc.perform(options("/api/v1/improvement-analyses")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "authorization"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(ACAO));
	}

	// --- 정상 응답 ---

	@Test
	void get_has_cors_headers_and_exposes_request_id() throws Exception {
		mvc.perform(get("/api/v1/facility-search-options").header("Origin", ALLOWED))
				.andExpect(status().isOk())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(header().string("Access-Control-Expose-Headers", "X-Request-Id, Retry-After"))
				.andExpect(header().stringValues("Vary", hasItem("Origin")))
				.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void post_has_cors_headers() throws Exception {
		// A04는 facilityId 필수. ALWAYS_OPEN 시설은 후보 0건이라 LLM을 호출하지 않는다
		FacilityType type = typeRepository.save(FacilityType.builder().name("cors-type")
				.chipGroup(ChipGroup.SPORTS).build());
		Facility f = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
				.sourceKey("cors-1").district("수지구").accessType(AccessType.ALWAYS_OPEN).name("cors 시설")
				.address("주소").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
		try {
			mvc.perform(post("/api/v1/improvement-analyses").header("Origin", ALLOWED)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"facilityId\":\"" + f.getFacilityId()
									+ "\",\"criteria\":{\"dayOfWeek\":1,\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}"))
					.andExpect(status().isOk())
					.andExpect(header().string(ACAO, ALLOWED))
					.andExpect(jsonPath("$.data.candidateCount").value(0));
		} finally {
			facilityRepository.delete(f);
			typeRepository.delete(type);
		}
	}

	// --- 오류 응답에도 헤더 ---

	@Test
	void bad_request_envelope_has_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/facilities").header("Origin", ALLOWED))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
	}

	@Test
	void unknown_path_404_envelope_has_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/none").header("Origin", ALLOWED))
				.andExpect(status().isNotFound())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void facility_not_found_envelope_has_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/facilities/999999")
						.param("dayOfWeek", "1").param("startTime", "10:00").param("endTime", "12:00")
						.header("Origin", ALLOWED))
				.andExpect(status().isNotFound())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"));
	}

	@Test
	void method_not_allowed_envelope_has_cors_headers() throws Exception {
		mvc.perform(post("/api/v1/facility-search-options").header("Origin", ALLOWED))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void internal_error_envelope_has_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/test-cors/500").header("Origin", ALLOWED))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
	}

	@Test
	void data_unavailable_envelope_has_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/test-cors/503").header("Origin", ALLOWED))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string(ACAO, ALLOWED))
				.andExpect(jsonPath("$.error.code").value("DATA_UNAVAILABLE"));
	}

	// --- 허용하지 않는 경우 ---

	@Test
	void denied_origin_get_is_403_without_cors_headers() throws Exception {
		mvc.perform(get("/api/v1/facility-search-options").header("Origin", DENIED))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(ACAO))
				.andExpect(header().exists("X-Request-Id"))
				.andExpect(content().string("Invalid CORS request"));
	}

	@Test
	void denied_origin_preflight_is_403_without_cors_headers() throws Exception {
		mvc.perform(options("/api/v1/facility-search-options")
						.header("Origin", DENIED)
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(ACAO));
	}

	@Test
	void unlisted_method_from_allowed_origin_is_403() throws Exception {
		// DELETE는 허용 메서드가 아니라 CORS 단계에서 거부된다(405까지 가지 않음)
		mvc.perform(delete("/api/v1/facility-search-options").header("Origin", ALLOWED))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(ACAO));
	}

	@Test
	void request_without_origin_is_unchanged() throws Exception {
		mvc.perform(get("/api/v1/facility-search-options"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(ACAO));
	}

	@Test
	void path_outside_api_has_no_cors_headers() throws Exception {
		mvc.perform(get("/v3/api-docs").header("Origin", ALLOWED))
				.andExpect(header().doesNotExist(ACAO));
	}
}
