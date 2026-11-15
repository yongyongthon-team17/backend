package com.jigeumgayong.backend.busarrival.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 실제 클라이언트를 로컬 HTTP 서버(JDK 내장)에 붙여 요청 형식·타임아웃·키 비노출을 확인한다.
 * 응답 JSON은 실제 샘플을 받기 전 가정 형식이다(ArrivalResponseParser 참고).
 */
class GyeonggiBusArrivalClientTest {

    static final String KEY = "secret+key/with==";

    HttpServer server;
    final AtomicReference<String> lastQuery = new AtomicReference<>();
    volatile int statusCode = 200;
    volatile long delayMs = 0;
    volatile String body = """
            {"response":{"msgHeader":{"resultCode":0},"msgBody":{"busArrivalList":[
              {"routeName":"10","predictTime1":3,"locationNo1":2},
              {"routeName":"5005","predictTime1":"","locationNo1":""}
            ]}}}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/arrivals", ex -> {
            lastQuery.set(ex.getRequestURI().getRawQuery());
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 먼저 끊은 경우
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GyeonggiBusArrivalClient client(Duration timeout) {
        BusApiProperties props = new BusApiProperties(true, KEY,
                "http://127.0.0.1:" + server.getAddress().getPort(), "/arrivals", timeout, Duration.ofSeconds(30));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new GyeonggiBusArrivalClient(props,
                RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build());
    }

    @Test
    void 정상_응답을_읽고_GGB_접두어를_뗀_stationId와_인코딩된_키로_요청한다() {
        List<RouteArrival> result = client(Duration.ofSeconds(3)).fetchArrivals("GGB228001782");

        assertThat(result).containsExactly(new RouteArrival("10", 3, 2), new RouteArrival("5005", null, null));
        assertThat(lastQuery.get())
                .contains("stationId=228001782")
                .contains("serviceKey=secret%2Bkey%2Fwith%3D%3D")
                .contains("format=json");
    }

    @Test
    void HTTP_오류는_BusApiException이고_메시지에_키가_없다() {
        statusCode = 500;
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).fetchArrivals("GGB228001782"))
                .isInstanceOf(BusApiException.class)
                .hasMessage("HTTP 500")
                .hasNoCause();
    }

    @Test
    void 읽기_타임아웃은_제한시간_안에_BusApiException이고_메시지에_키가_없다() {
        delayMs = 1_500;
        long start = System.nanoTime();
        assertThatThrownBy(() -> client(Duration.ofMillis(300)).fetchArrivals("GGB228001782"))
                .isInstanceOf(BusApiException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("serviceKey"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_200);
    }

    @Test
    void 결과코드가_오류면_실패_결과없음이면_빈목록() {
        body = "{\"response\":{\"msgHeader\":{\"resultCode\":30},\"msgBody\":null}}";
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).fetchArrivals("GGB1"))
                .isInstanceOf(BusApiException.class).hasMessage("resultCode=30");

        body = "{\"response\":{\"msgHeader\":{\"resultCode\":4},\"msgBody\":null}}";
        assertThat(client(Duration.ofSeconds(3)).fetchArrivals("GGB1")).isEmpty();
    }

    @Test
    void 결과가_1건이면_객체로_와도_읽고_JSON이_아니면_실패() {
        body = "{\"response\":{\"msgBody\":{\"busArrivalList\":{\"routeName\":\"66-4\",\"predictTime1\":\"9\",\"locationNo1\":\"5\"}}}}";
        assertThat(client(Duration.ofSeconds(3)).fetchArrivals("GGB1"))
                .containsExactly(new RouteArrival("66-4", 9, 5));

        body = "<OpenAPI_ServiceResponse>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</OpenAPI_ServiceResponse>";
        assertThatThrownBy(() -> client(Duration.ofSeconds(3)).fetchArrivals("GGB1"))
                .isInstanceOf(BusApiException.class);
    }

    @Test
    void 정류장ID_변환은_GGB_접두어만_뗀다() {
        assertThat(StationIds.toArrivalStationId("GGB228001782")).isEqualTo("228001782");
        assertThat(StationIds.toArrivalStationId("228001782")).isEqualTo("228001782");
    }

    @Test
    void 설정_toString에_키가_나오지_않는다() {
        BusApiProperties props = new BusApiProperties(true, KEY, "http://x", "/a",
                Duration.ofSeconds(3), Duration.ofSeconds(30));
        assertThat(props.toString()).doesNotContain(KEY).contains("****");
    }
}
