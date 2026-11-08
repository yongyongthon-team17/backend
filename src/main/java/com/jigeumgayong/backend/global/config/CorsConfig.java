package com.jigeumgayong.backend.global.config;

import com.jigeumgayong.backend.global.web.RequestIdHolder;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * /api/** CORS. 허용 출처는 app.cors.allowed-origins(쉼표 구분)로 받고, 비어 있으면 교차 출처를 허용하지 않는다.
 * WebMvcConfigurer.addCorsMappings는 핸들러를 찾은 요청에만 적용돼 404(경로 없음)·405 오류 응답에 헤더가 빠지므로
 * 서블릿 필터(CorsFilter)로 등록한다. RequestIdFilter 다음 순서라 CORS 거부(403)에도 X-Request-Id가 남는다.
 */
@Slf4j
@Configuration
public class CorsConfig {

	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilter(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
		List<String> origins = parseOrigins(allowedOrigins);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		if (origins.isEmpty()) {
			// 설정을 등록하지 않으면 CorsFilter는 헤더 없이 통과시킨다 → 브라우저가 교차 출처 응답을 차단
			log.info("CORS 허용 출처 없음: 교차 출처 요청을 허용하지 않음");
		} else {
			CorsConfiguration config = new CorsConfiguration();
			config.setAllowedOrigins(origins);
			config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
			config.setAllowedHeaders(List.of("Content-Type", "Accept", RequestIdHolder.HEADER));
			config.setExposedHeaders(List.of(RequestIdHolder.HEADER));
			config.setAllowCredentials(false);
			config.setMaxAge(3600L);
			source.registerCorsConfiguration("/api/**", config);
			log.info("CORS 허용 출처: {}", origins);
		}
		FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
		bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
		return bean;
	}

	static List<String> parseOrigins(String value) {
		if (value == null) {
			return List.of();
		}
		return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
	}
}
