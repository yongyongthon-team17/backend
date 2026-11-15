package com.jigeumgayong.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 테스트 컨텍스트에서 실제 경기도 API 클라이언트 대신 FakeBusArrivalClient를 쓰게 한다. */
@TestConfiguration
public class FakeBusArrivalConfig {

    @Bean
    @Primary
    public FakeBusArrivalClient fakeBusArrivalClient() {
        return new FakeBusArrivalClient();
    }
}
