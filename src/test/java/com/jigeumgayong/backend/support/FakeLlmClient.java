package com.jigeumgayong.backend.support;

import com.jigeumgayong.backend.analysis.llm.LlmClient;
import com.jigeumgayong.backend.analysis.llm.LlmUnavailableException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 키 없이 테스트하기 위한 가짜 LLM. 호출 수를 세고, 응답·지연·실패를 지정할 수 있다. */
public class FakeLlmClient implements LlmClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public final AtomicInteger calls = new AtomicInteger();
    /** 입력(facts JSON) → 응답 JSON. 기본은 facts에 맞는 유효한 응답 */
    public volatile Function<String, String> responder = FakeLlmClient::validResponse;
    public volatile long delayMillis = 0;
    public volatile boolean fail = false;

    public void reset() {
        calls.set(0);
        responder = FakeLlmClient::validResponse;
        delayMillis = 0;
        fail = false;
    }

    @Override
    public String generateJson(String instructions, String input, String schemaName, Map<String, Object> schema) {
        calls.incrementAndGet();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmUnavailableException("interrupted");
            }
        }
        if (fail) {
            throw new LlmUnavailableException("fake failure");
        }
        return responder.apply(input);
    }

    @Override
    public String modelName() {
        return "fake-model";
    }

    /** facts에 있는 시각만 써서 검증을 통과하는 응답을 만든다. */
    public static String validResponse(String factsJson) {
        JsonNode facts = MAPPER.readTree(factsJson);
        String label = facts.path("spaceLabel").asString();
        String end = facts.path("requestEnd").asString();
        boolean extendable = facts.path("extendable").asBoolean();
        String close = extendable ? "\"" + end + "\"" : "null";
        String alt = extendable
                ? label + " 운영을 " + end + "까지 시범 운영하는 방안을 검토할 수 있습니다."
                : "조기 개방이나 휴관일 시범 운영을 검토할 수 있습니다.";
        return "{\"proposedClosingTime\":" + close + ",\"alternatives\":[\"" + alt
                + "\"],\"integratedSuggestion\":\"운영시간 조정을 검토할 수 있습니다. 예약 필요 여부는 별도 확인하세요.\"}";
    }
}
