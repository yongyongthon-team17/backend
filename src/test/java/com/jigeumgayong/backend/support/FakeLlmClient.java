package com.jigeumgayong.backend.support;

import com.jigeumgayong.backend.analysis.llm.LlmClient;
import com.jigeumgayong.backend.analysis.llm.LlmUnavailableException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 키 없이 테스트하기 위한 가짜 LLM. 호출 수를 세고, 응답·지연·실패를 지정할 수 있다. */
public class FakeLlmClient implements LlmClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public final AtomicInteger calls = new AtomicInteger();
    /** 입력(facts JSON) → 응답 JSON. 기본은 facts에 맞는 유효한 응답 */
    public volatile Function<String, String> responder = FakeLlmClient::validResponse;
    public volatile long delayMillis = 0;
    public volatile boolean fail = false;
    /** 환경 변수로 모델·추론 수준을 바꾼 상황을 흉내 낸다 */
    public volatile String model = "fake-model";
    public volatile String effort = "low";

    public void reset() {
        calls.set(0);
        responder = FakeLlmClient::validResponse;
        delayMillis = 0;
        fail = false;
        model = "fake-model";
        effort = "low";
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
        return model;
    }

    @Override
    public String reasoningEffort() {
        return effort;
    }

    /**
     * facts에 있는 시각과 관찰 id만 써서 검증을 통과하는 응답을 만든다.
     * 자기 공간의 관찰이 있는 공간마다 제안을 하나 만들고, 하나도 없으면 첫 공간에 첫 관찰을 근거로 제안한다.
     */
    public static String validResponse(String factsJson) {
        JsonNode facts = MAPPER.readTree(factsJson);
        String end = facts.path("requestEnd").asString();
        JsonNode observations = facts.path("observations");
        String firstId = observations.get(0).path("id").asString();
        ArrayNode spaces = MAPPER.createArrayNode();
        for (JsonNode space : facts.path("spaces")) {
            String type = space.path("spaceType").asString();
            String ownId = null;
            for (JsonNode o : observations) {
                if (type.equals(o.path("spaceType").asString(""))) {
                    ownId = o.path("id").asString();
                    break;
                }
            }
            if (ownId == null) {
                continue;
            }
            spaces.add(proposal(space, end, ownId));
        }
        if (spaces.isEmpty()) {
            spaces.add(proposal(facts.path("spaces").get(0), end, firstId));
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("spaces", spaces);
        root.put("integratedSuggestion", "운영시간 조정을 검토할 수 있습니다. 예약 필요 여부는 별도 확인하세요.");
        root.putArray("integratedBasedOn").add(firstId);
        return root.toString();
    }

    private static ObjectNode proposal(JsonNode space, String end, String basedOn) {
        boolean extendable = space.path("extendable").asBoolean();
        String label = space.path("spaceLabel").asString();
        ObjectNode s = MAPPER.createObjectNode();
        s.put("spaceType", space.path("spaceType").asString());
        if (extendable) {
            s.put("proposedClosingTime", end);
        } else {
            s.putNull("proposedClosingTime");
        }
        ObjectNode alt = MAPPER.createObjectNode();
        alt.put("text", extendable
                ? label + " 운영을 " + end + "까지 시범 운영하는 방안을 검토할 수 있습니다."
                : "조기 개방이나 휴관일 시범 운영을 검토할 수 있습니다.");
        alt.putArray("basedOn").add(basedOn);
        s.putArray("alternatives").add(alt);
        return s;
    }
}
