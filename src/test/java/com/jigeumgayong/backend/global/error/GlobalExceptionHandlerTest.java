package com.jigeumgayong.backend.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.global.response.ApiResponse;
import com.jigeumgayong.backend.global.web.RequestIdFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new DummyController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.setValidator(validator())
				.addFilters(new RequestIdFilter())
				.build();
	}

	private static LocalValidatorFactoryBean validator() {
		LocalValidatorFactoryBean v = new LocalValidatorFactoryBean();
		v.afterPropertiesSet();
		return v;
	}

	@Test
	void success_envelope_has_data_and_meta() throws Exception {
		mvc.perform(get("/ok").header("X-Request-Id", "req-test-1"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Request-Id", "req-test-1"))
				.andExpect(jsonPath("$.data.hello").value("world"))
				.andExpect(jsonPath("$.meta.requestId").value("req-test-1"))
				.andExpect(jsonPath("$.meta.generatedAt").isString())
				.andExpect(jsonPath("$.meta.warnings").isEmpty());
	}

	@Test
	void business_exception_uses_error_code_and_details() throws Exception {
		mvc.perform(get("/business"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"))
				.andExpect(jsonPath("$.error.details[0].field").value("endTime"))
				.andExpect(jsonPath("$.error.details[0].reason").value("MUST_BE_AFTER_START_TIME"))
				.andExpect(jsonPath("$.error.requestId").isString());
	}

	@Test
	void missing_param_is_400_invalid_request() throws Exception {
		mvc.perform(get("/param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("dayOfWeek"))
				.andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
	}

	@Test
	void type_mismatch_is_400_invalid_request() throws Exception {
		mvc.perform(get("/param").param("dayOfWeek", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"));
	}

	@Test
	void malformed_json_is_400_invalid_request_with_empty_details() throws Exception {
		mvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{bad"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details").isEmpty());
	}

	@Test
	void wrong_method_is_405_with_allow_header() throws Exception {
		mvc.perform(post("/ok"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().exists("Allow"))
				.andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void wrong_media_type_is_415() throws Exception {
		mvc.perform(post("/body").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void unexpected_exception_is_500_without_leaking_message() throws Exception {
		mvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
	}

	@Test
	void data_access_failure_is_503_data_unavailable_without_leaking_message() throws Exception {
		mvc.perform(get("/db"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("DATA_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("필요한 데이터를 사용할 수 없습니다."))
				.andExpect(jsonPath("$.error.details").isEmpty())
				.andExpect(jsonPath("$.error.requestId").isString());
	}


	@Test
	void transaction_failure_is_503_data_unavailable_without_leaking_message() throws Exception {
		mvc.perform(get("/tx"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("DATA_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("필요한 데이터를 사용할 수 없습니다."))
				.andExpect(jsonPath("$.error.details").isEmpty());
	}

	@Test
	void missing_header_is_400_with_header_name_as_field() throws Exception {
		mvc.perform(get("/header"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("X-Need"))
				.andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
	}

	@Test
	void parameter_constraint_violation_is_400_out_of_range() throws Exception {
		mvc.perform(get("/min").param("dayOfWeek", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("dayOfWeek"))
				.andExpect(jsonPath("$.error.details[0].reason").value("OUT_OF_RANGE"));
	}

	@Test
	void constraint_violation_exception_is_400_out_of_range() throws Exception {
		mvc.perform(get("/violation"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.error.details[0].field").value("dayOfWeek"))
				.andExpect(jsonPath("$.error.details[0].reason").value("OUT_OF_RANGE"));
	}

	@Test
	void error_request_id_equals_response_header_for_sent_and_generated_ids() throws Exception {
		String sent = mvc.perform(get("/boom").header("X-Request-Id", "req-same-1"))
				.andExpect(header().string("X-Request-Id", "req-same-1"))
				.andExpect(jsonPath("$.error.requestId").value("req-same-1"))
				.andReturn().getResponse().getHeader("X-Request-Id");
		org.assertj.core.api.Assertions.assertThat(sent).isEqualTo("req-same-1");

		var res = mvc.perform(get("/boom")).andReturn().getResponse();
		String header = res.getHeader("X-Request-Id");
		org.assertj.core.api.Assertions.assertThat(header).startsWith("req-");
		org.assertj.core.api.Assertions.assertThat(res.getContentAsString()).contains("\"requestId\":\"" + header + "\"");
	}

	@Test
	void generated_at_is_second_precision_seoul_offset() throws Exception {
		mvc.perform(get("/ok"))
				.andExpect(jsonPath("$.meta.generatedAt")
						.value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00")));
	}

	@RestController
	static class DummyController {

		@GetMapping("/tx")
		void tx() {
			throw new org.springframework.transaction.CannotCreateTransactionException("jdbc:secret-url");
		}

		@GetMapping("/header")
		String header(@RequestHeader("X-Need") String h) {
			return h;
		}

		@GetMapping("/min")
		String min(@RequestParam @Min(0) int dayOfWeek) {
			return "x";
		}

		@GetMapping("/violation")
		void violation() {
			record Req(@Min(0) int dayOfWeek) {
			}
			var v = Validation.buildDefaultValidatorFactory().getValidator().validate(new Req(-1));
			throw new ConstraintViolationException(v);
		}

		@GetMapping("/db")
		void db() {
			throw new org.springframework.dao.DataAccessResourceFailureException("jdbc:secret-url");
		}

		@GetMapping("/ok")
		ApiResponse<Map<String, String>> ok() {
			return ApiResponse.ok(Map.of("hello", "world"));
		}

		@GetMapping("/business")
		void business() {
			throw new BusinessException(ErrorCode.INVALID_TIME_RANGE, "종료 시간은 시작 시간보다 늦어야 합니다.",
					List.of(new ErrorDetail("endTime", "MUST_BE_AFTER_START_TIME")));
		}

		@GetMapping("/param")
		String param(@RequestParam int dayOfWeek) {
			return "x";
		}

		@PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
		String body(@RequestBody Map<String, Object> body) {
			return "x";
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("secret internal detail");
		}
	}
}
