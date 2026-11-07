package com.jigeumgayong.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 서버가 UTC여도 JVM 기본 시간대가 Asia/Seoul로 고정되는지 확인. */
@SpringBootTest
class DefaultTimeZoneTest {

	@Test
	void jvm_default_timezone_is_seoul() {
		assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Seoul");
	}
}
