package com.jigeumgayong.backend.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI().info(new Info()
				.title("지금가용 API")
				.version("v0.2")
				.description("공통 규칙: docs/07_API_공통규칙_응답타입.md"));
	}
}
