package com.jigeumgayong.backend.busarrival.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 실제 호출 응답(2026-11-15, stationId=228001782)에서 필요한 필드만 남긴 샘플로 파서를 확인한다.
 * 실제 응답은 routeName이 숫자(670)나 문자("31-1")로 오고, 두 번째 버스 값은 빈 문자열이다.
 */
class ArrivalResponseParserTest {

    static final String REAL_SAMPLE = """
            {"response":{"comMsgHeader":"","msgHeader":{"queryTime":"2026-11-15 12:55:59.196","resultCode":0,
            "resultMessage":"정상적으로 처리되었습니다."},"msgBody":{"busArrivalList":[
              {"flag":"PASS","locationNo1":7,"locationNo2":"","predictTime1":8,"predictTime2":"",
               "routeDestName":"동백역3번출구","routeId":241424008,"routeName":"31-1","stationId":228001782},
              {"flag":"PASS","locationNo1":8,"locationNo2":"","predictTime1":13,"predictTime2":"",
               "routeDestName":"푸르지오월드마크","routeId":228000010,"routeName":670,"stationId":228001782},
              {"flag":"PASS","locationNo1":2,"locationNo2":"","predictTime1":3,"predictTime2":"",
               "routeDestName":"A마트(마을)","routeId":241332003,"routeName":"810-1","stationId":228001782}
            ]}}}""";

    @Test
    void 실제_응답_샘플에서_노선_남은_분_남은_정류장을_읽는다() {
        List<RouteArrival> list = ArrivalResponseParser.parse(REAL_SAMPLE);

        assertThat(list).containsExactly(new RouteArrival("31-1", 8, 7), new RouteArrival("670", 13, 8),
                new RouteArrival("810-1", 3, 2));
    }
}
