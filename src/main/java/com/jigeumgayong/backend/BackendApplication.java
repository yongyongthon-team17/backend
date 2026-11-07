package com.jigeumgayong.backend;

import com.jigeumgayong.backend.global.response.Meta;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	// 서버가 UTC여도 LocalDateTime.now(), @CreationTimestamp, @UpdateTimestamp가 KST로 저장되게 JVM 기본 시간대를 고정
	static {
		TimeZone.setDefault(TimeZone.getTimeZone(Meta.ZONE));
	}

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
