package com.jigeumgayong.backend.analysis.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI(LLM) 호출 한도 설정(app.llm.rate-limit.*). 모든 한도는 윈도우 안에서 "실제로 OpenAI를 호출한 횟수"를 센다.
 * 캐시 HIT은 세지 않는다. 값은 환경 변수(APP_LLM_RATE_LIMIT_PER_CLIENT 등)로 행사 중에도 조정할 수 있다.
 *
 * @param enabled        false이면 한도를 적용하지 않는다
 * @param window         집계 윈도우 (슬라이딩)
 * @param perClient      개인(X-Client-Id)당 한도. UUID가 없으면 IP를 개인으로 본다
 * @param perIp          IP당 한도. UUID를 바꿔 우회하는 것을 막는 상한이라 공유 Wi-Fi를 고려해 넉넉히 둔다
 * @param global         서버 전체 한도. 크레딧 총량을 지키는 마지막 안전망
 * @param maxTrackedKeys 메모리 보호용 추적 키 상한(개인·IP 각각)
 */
@ConfigurationProperties("app.llm.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3m") Duration window,
        @DefaultValue("10") int perClient,
        @DefaultValue("150") int perIp,
        @DefaultValue("150") int global,
        @DefaultValue("20000") int maxTrackedKeys) {
}
