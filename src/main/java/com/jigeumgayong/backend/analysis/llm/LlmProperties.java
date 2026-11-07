package com.jigeumgayong.backend.analysis.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 설정(app.llm.*). 모델·추론 수준은 프로파일별 yaml에서 정한다.
 * apiKey는 환경 변수 OPENAI_API_KEY로만 채운다(값을 코드·yaml·로그에 두지 않는다).
 *
 * @param timeout                  호출 1회 제한 시간
 * @param requestDeadline          A04 요청 전체 제한 시간(초과하면 남은 항목은 UNAVAILABLE)
 * @param maxGenerationsPerRequest 요청 1건에서 새로 생성할 수 있는 최대 개수(캐시 HIT 제외)
 */
@ConfigurationProperties("app.llm")
public record LlmProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("gpt-6-luna") String model,
        @DefaultValue("low") String reasoningEffort,
        @DefaultValue("20s") Duration timeout,
        @DefaultValue("30s") Duration requestDeadline,
        @DefaultValue("2000") int maxOutputTokens,
        @DefaultValue("4") int maxConcurrency,
        @DefaultValue("10") int maxGenerationsPerRequest) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 로그에 키가 찍히지 않도록 가린다. */
    @Override
    public String toString() {
        return "LlmProperties[model=" + model + ", reasoningEffort=" + reasoningEffort
                + ", apiKey=" + (hasApiKey() ? "****" : "(unset)") + "]";
    }
}
