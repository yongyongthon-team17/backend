package com.jigeumgayong.backend.analysis.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 네트워크·키 없이 요청 형태와 응답 파싱을 확인한다. */
class OpenAiLlmClientTest {

    static final String KEY = "test-key-not-real";
    static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private LlmProperties props(String apiKey) {
        return new LlmProperties(apiKey, "https://api.test/v1", "gpt-6-luna", "low",
                Duration.ofSeconds(20), Duration.ofSeconds(30), 2000, 4, 10);
    }

    private record Setup(OpenAiLlmClient client, MockRestServiceServer server) {
    }

    private Setup setup(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Setup(new OpenAiLlmClient(props(apiKey), builder.build()), server);
    }

    private static final String OK_BODY = """
            {"id":"resp_1","status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","status":"completed","content":[
                {"type":"output_text","text":"{\\"a\\":1}","annotations":[]}]}]}""";

    @Test
    void 요청_본문은_Responses_API_구조화_출력_형태다() {
        Setup s = setup(KEY);
        s.server().expect(requestTo("https://api.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.model").value("gpt-6-luna"))
                .andExpect(jsonPath("$.reasoning.effort").value("low"))
                .andExpect(jsonPath("$.max_output_tokens").value(2000))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.name").value("improvement_proposal"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.type").value("object"))
                .andExpect(jsonPath("$.input[0].role").value("developer"))
                .andExpect(jsonPath("$.input[0].content").value("지시문"))
                .andExpect(jsonPath("$.input[1].role").value("user"))
                .andExpect(jsonPath("$.input[1].content").value("{\"facts\":1}"))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        String text = s.client().generateJson("지시문", "{\"facts\":1}", "improvement_proposal", SCHEMA);

        assertThat(text).isEqualTo("{\"a\":1}");
        s.server().verify();
    }

    @Test
    void 모델명은_설정값이다() {
        assertThat(setup(KEY).client().modelName()).isEqualTo("gpt-6-luna");
    }

    @Test
    void refusal은_실패다() {
        Setup s = setup(KEY);
        s.server().expect(requestTo("https://api.test/v1/responses")).andRespond(withSuccess("""
                {"status":"completed","output":[{"type":"message","content":[
                  {"type":"refusal","refusal":"거부"}]}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> s.client().generateJson("i", "x", "n", SCHEMA))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void incomplete_응답은_실패다() {
        Setup s = setup(KEY);
        s.server().expect(requestTo("https://api.test/v1/responses")).andRespond(withSuccess("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> s.client().generateJson("i", "x", "n", SCHEMA))
                .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("incomplete");
    }

    @Test
    void 텍스트가_없는_응답과_JSON이_아닌_응답은_실패다() {
        Setup s = setup(KEY);
        s.server().expect(requestTo("https://api.test/v1/responses")).andRespond(
                withSuccess("{\"status\":\"completed\",\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> s.client().generateJson("i", "x", "n", SCHEMA))
                .isInstanceOf(LlmUnavailableException.class);

        Setup t = setup(KEY);
        t.server().expect(requestTo("https://api.test/v1/responses")).andRespond(
                withSuccess("<html>oops</html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> t.client().generateJson("i", "x", "n", SCHEMA))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void HTTP_오류는_상태코드만_담은_LlmUnavailableException이다() {
        Setup s = setup(KEY);
        s.server().expect(requestTo("https://api.test/v1/responses")).andRespond(withServerError());

        assertThatThrownBy(() -> s.client().generateJson("i", "x", "n", SCHEMA))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void 키가_없으면_HTTP_호출_없이_실패한다() {
        for (String blank : new String[] { "", "   ", null }) {
            Setup s = setup(blank);
            // 기대(expect)를 걸지 않았으므로 요청이 나가면 MockRestServiceServer가 실패한다
            assertThatThrownBy(() -> s.client().generateJson("i", "x", "n", SCHEMA))
                    .isInstanceOf(LlmUnavailableException.class).hasMessageContaining("OPENAI_API_KEY");
            s.server().verify();
        }
    }

    @Test
    void 설정_toString은_키를_가린다() {
        assertThat(props(KEY).toString()).doesNotContain(KEY).contains("****");
        assertThat(props("").toString()).contains("(unset)");
    }
}
