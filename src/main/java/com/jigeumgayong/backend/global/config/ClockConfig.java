package com.jigeumgayong.backend.global.config;

import com.jigeumgayong.backend.global.response.Meta;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 요일 등 "오늘" 계산을 테스트에서 고정할 수 있게 Asia/Seoul Clock을 빈으로 제공. */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.system(Meta.ZONE);
	}
}