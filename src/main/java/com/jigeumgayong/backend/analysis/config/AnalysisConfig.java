package com.jigeumgayong.backend.analysis.config;

import com.jigeumgayong.backend.analysis.llm.LlmClient;
import com.jigeumgayong.backend.analysis.llm.LlmProperties;
import com.jigeumgayong.backend.analysis.llm.OpenAiLlmClient;
import com.jigeumgayong.backend.analysis.ratelimit.RateLimitProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({ LlmProperties.class, RateLimitProperties.class })
public class AnalysisConfig {

    public static final String LLM_EXECUTOR = "improvementExecutor";

    /** 운영 LLM 클라이언트. 호출당 제한 시간(app.llm.timeout)을 연결·읽기 타임아웃으로 건다. */
    @Bean
    public LlmClient llmClient(LlmProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
        return new OpenAiLlmClient(props, restClient);
    }

    /** 후보별 LLM 호출을 병렬로 돌리는 고정 크기 풀 */
    @Bean(name = LLM_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService improvementExecutor(LlmProperties props) {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(Math.max(1, props.maxConcurrency()), r -> {
            Thread t = new Thread(r, "improvement-llm-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
