package com.jigeumgayong.backend.busarrival.client;

import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 경기도 버스도착정보 API 실제 구현. 타임아웃은 RestClient 요청 팩토리(연결·읽기)에 건다.
 * 키는 쿼리 파라미터로만 보내고, 예외 메시지·로그에 URI를 남기지 않는다.
 */
public class GyeonggiBusArrivalClient implements BusArrivalClient {

    // 요청 파라미터명(공공데이터포털 경기도 API 관례, [미검증]). 실제 문서·샘플로 확인 후 맞춘다
    static final String PARAM_KEY = "serviceKey";
    static final String PARAM_STATION = "stationId";
    static final String PARAM_FORMAT = "format";

    private final BusApiProperties props;
    private final RestClient restClient;

    public GyeonggiBusArrivalClient(BusApiProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public List<RouteArrival> fetchArrivals(String stopId) {
        String stationId = StationIds.toArrivalStationId(stopId);
        String body;
        try {
            // URI 변수로 넘겨 값 전체를 인코딩한다(키의 +, /, = 보존). 키는 포털의 '디코딩' 키를 넣는다
            body = restClient.get()
                    .uri(props.arrivalPath() + "?" + PARAM_KEY + "={key}&" + PARAM_STATION + "={station}&"
                            + PARAM_FORMAT + "=json", props.apiKey(), stationId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new BusApiException("HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            // 타임아웃·연결 실패. 원인 메시지에 키가 든 URI가 있어 붙이지 않는다
            throw new BusApiException("호출 실패(" + e.getClass().getSimpleName() + ")");
        }
        return ArrivalResponseParser.parse(body);
    }
}
