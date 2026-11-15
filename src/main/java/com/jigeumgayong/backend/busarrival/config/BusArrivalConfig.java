package com.jigeumgayong.backend.busarrival.config;

import com.jigeumgayong.backend.busarrival.client.BusApiProperties;
import com.jigeumgayong.backend.busarrival.client.BusArrivalClient;
import com.jigeumgayong.backend.busarrival.client.GyeonggiBusArrivalClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(BusApiProperties.class)
public class BusArrivalConfig {

    public static final String BUS_EXECUTOR = "busArrivalExecutor";

    /** 운영 클라이언트. 호출당 제한 시간(app.bus.timeout)을 연결·읽기 타임아웃으로 건다. */
    @Bean
    public BusArrivalClient busArrivalClient(BusApiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeout());
        factory.setReadTimeout(props.timeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
        return new GyeonggiBusArrivalClient(props, restClient);
    }

    /** 정류장(최대 3곳)별 호출을 병렬로 돌리는 작은 풀 */
    @Bean(name = BUS_EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService busArrivalExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "bus-arrival-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
