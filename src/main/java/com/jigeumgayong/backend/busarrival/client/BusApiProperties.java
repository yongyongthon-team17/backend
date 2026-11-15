package com.jigeumgayong.backend.busarrival.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실시간 버스 도착(A06) 설정(app.bus.*). apiKey는 환경 변수 APP_BUS_API_KEY로만 채운다.
 *
 * @param realtimeEnabled false(기본)면 외부 호출 없이 NOT_ENABLED
 * @param baseUrl         공공데이터포털 경기도 서비스 기준 주소. 실제 응답으로 확인 전이라 설정값으로 둔다
 * @param arrivalPath     정류소별 도착 목록 오퍼레이션 경로
 * @param timeout         외부 호출 1회 제한 시간
 * @param cacheTtl        시설 기준 결과 캐시 시간
 */
@ConfigurationProperties("app.bus")
public record BusApiProperties(
        @DefaultValue("false") boolean realtimeEnabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://apis.data.go.kr/6410000") String baseUrl,
        @DefaultValue("/busarrivalservice/v2/getBusArrivalListv2") String arrivalPath,
        @DefaultValue("3s") Duration timeout,
        @DefaultValue("30s") Duration cacheTtl) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 로그에 키가 찍히지 않도록 가린다. */
    @Override
    public String toString() {
        return "BusApiProperties[realtimeEnabled=" + realtimeEnabled + ", baseUrl=" + baseUrl
                + ", arrivalPath=" + arrivalPath + ", apiKey=" + (hasApiKey() ? "****" : "(unset)") + "]";
    }
}
