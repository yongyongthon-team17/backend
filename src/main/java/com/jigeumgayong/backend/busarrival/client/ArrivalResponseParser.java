package com.jigeumgayong.backend.busarrival.client;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경기도 버스도착정보 응답(JSON)에서 필드를 읽는 유일한 곳.
 * [미검증] 아래 경로·필드명·결과 코드는 실제 응답 샘플을 받기 전 가정값이다. 샘플을 받으면 이 클래스만 맞춘다.
 */
final class ArrivalResponseParser {

    // 응답 구조(가정): { "response": { "msgHeader": { "resultCode": 0 }, "msgBody": { "busArrivalList": [ ... ] } } }
    static final String ROOT = "response";
    static final String HEADER = "msgHeader";
    static final String RESULT_CODE = "resultCode";
    static final String BODY = "msgBody";
    static final String LIST = "busArrivalList";
    static final String ROUTE_NAME = "routeName";
    static final String MINUTES_LEFT = "predictTime1";
    static final String STOPS_LEFT = "locationNo1";

    /** 정상(0)과 결과 없음(4)만 성공으로 본다(가정). 그 밖의 코드는 실패 */
    static final String RESULT_OK = "0";
    static final String RESULT_NO_DATA = "4";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ArrivalResponseParser() {
    }

    static List<RouteArrival> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (JacksonException e) {
            throw new BusApiException("응답 JSON 해석 실패");
        }
        JsonNode response = root == null ? null : root.path(ROOT);
        if (response == null || response.isMissingNode()) {
            throw new BusApiException("응답 형식이 예상과 다름");
        }
        String code = text(response.path(HEADER).path(RESULT_CODE));
        if (code != null && !RESULT_OK.equals(code) && !RESULT_NO_DATA.equals(code)) {
            throw new BusApiException("resultCode=" + code);
        }
        JsonNode list = response.path(BODY).path(LIST);
        List<RouteArrival> result = new ArrayList<>();
        if (list.isArray()) {
            list.forEach(item -> result.add(toArrival(item)));
        } else if (list.isObject()) {
            // 결과가 1건이면 배열이 아니라 객체로 올 수 있다(공공데이터 API에서 흔함)
            result.add(toArrival(list));
        }
        return result;
    }

    private static RouteArrival toArrival(JsonNode item) {
        return new RouteArrival(text(item.path(ROUTE_NAME)), integer(item.path(MINUTES_LEFT)),
                integer(item.path(STOPS_LEFT)));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 숫자 또는 숫자 문자열. 빈 값·해석 불가는 null */
    private static Integer integer(JsonNode node) {
        String s = text(node);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
