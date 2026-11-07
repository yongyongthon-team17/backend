package com.jigeumgayong.backend.analysis.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI Responses API(POST /responses) 호출. 구조화 출력(json_schema, strict)으로 JSON만 받는다.
 * 키가 없으면 네트워크 호출 없이 바로 LlmUnavailableException.
 * 요청·응답 본문과 키는 로그에 남기지 않는다.
 */
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final LlmProperties props;
    private final RestClient restClient;

    public OpenAiLlmClient(LlmProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
        if (!props.hasApiKey()) {
            log.warn("OPENAI_API_KEY가 설정되지 않았습니다. AI 개선안은 LLM_UNAVAILABLE로 응답합니다.");
        }
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public String generateJson(String instructions, String input, String schemaName, Map<String, Object> schema) {
        if (!props.hasApiKey()) {
            throw new LlmUnavailableException("OPENAI_API_KEY not set");
        }
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", schemaName);
        format.put("schema", schema);
        format.put("strict", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("input", List.of(
                Map.of("role", "developer", "content", instructions),
                Map.of("role", "user", "content", input)));
        body.put("reasoning", Map.of("effort", props.reasoningEffort()));
        body.put("text", Map.of("format", format));
        body.put("max_output_tokens", props.maxOutputTokens());
        body.put("store", false);

        String response;
        try {
            response = restClient.post().uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new LlmUnavailableException("OpenAI HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new LlmUnavailableException("OpenAI call failed: " + e.getClass().getSimpleName());
        }
        return extractText(response);
    }

    /** output[] 중 message의 output_text를 꺼낸다. refusal·incomplete·빈 응답은 실패. */
    static String extractText(String response) {
        JsonNode root;
        try {
            root = MAPPER.readTree(response);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("OpenAI response is not JSON");
        }
        String status = root.path("status").asString("");
        if (!status.isEmpty() && !"completed".equals(status)) {
            throw new LlmUnavailableException("OpenAI response status=" + status);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asString(""))) {
                continue; // reasoning 등
            }
            for (JsonNode content : item.path("content")) {
                String type = content.path("type").asString("");
                if ("refusal".equals(type)) {
                    throw new LlmUnavailableException("OpenAI refused the request");
                }
                if ("output_text".equals(type)) {
                    text.append(content.path("text").asString(""));
                }
            }
        }
        if (text.isEmpty()) {
            throw new LlmUnavailableException("OpenAI response has no output_text");
        }
        return text.toString();
    }
}
