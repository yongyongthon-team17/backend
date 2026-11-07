package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import org.junit.jupiter.api.Test;

class ImprovementPayloadTest {

    /** 현재 09:00-18:00(1080), 희망 19:00-21:00, 종료 연장으로 해결 가능 */
    static Candidate candidate(boolean extendable, boolean reservation) {
        return new Candidate(null, SpaceType.GENERAL, "종합자료실", null, null, reservation,
                1080, extendable, 1, 1140, 1260);
    }

    static final String FACTS = "{\"spaceLabel\":\"종합자료실\",\"requestStart\":\"19:00\",\"requestEnd\":\"21:00\","
            + "\"currentClosingTime\":\"18:00\",\"openIntervals\":[{\"openTime\":\"09:00\",\"closeTime\":\"18:00\"}]}";

    static String llm(String close, String alternatives, String suggestion) {
        return "{\"proposedClosingTime\":" + close + ",\"alternatives\":" + alternatives
                + ",\"integratedSuggestion\":\"" + suggestion + "\"}";
    }

    private static final String OK_ALT = "[\"종합자료실을 21:00까지 시범 운영하는 방안을 검토합니다.\"]";

    @Test
    void 정상_LLM_응답은_분_단위_payload가_된다() {
        ImprovementPayload p = ImprovementPayload.fromLlm(
                llm("\"21:00\"", OK_ALT, "21시까지 연장을 검토할 수 있습니다."), candidate(true, false), FACTS);

        assertThat(p.proposedCloseMin()).isEqualTo(1260);
        assertThat(p.alternatives()).hasSize(1);
        assertThat(p.toJson()).startsWith("{").contains("\"proposedCloseMin\":1260");
    }

    @Test
    void 저장_형태로_왕복한다() {
        ImprovementPayload p = ImprovementPayload.fromLlm(
                llm("\"21:00\"", OK_ALT, "검토할 수 있습니다."), candidate(true, false), FACTS);

        ImprovementPayload stored = ImprovementPayload.fromStored(p.toJson(), candidate(true, false), FACTS);

        assertThat(stored).isEqualTo(p);
    }

    @Test
    void 제안_종료가_null이어도_연장_가능이면_통과한다() {
        ImprovementPayload p = ImprovementPayload.fromLlm(
                llm("null", OK_ALT, "검토할 수 있습니다."), candidate(true, false), FACTS);
        assertThat(p.proposedCloseMin()).isNull();
    }

    @Test
    void 손상_JSON과_객체가_아닌_JSON은_거부한다() {
        for (String bad : new String[] { "not json", "[]", "\"x\"", "42", "" }) {
            assertThatThrownBy(() -> ImprovementPayload.fromStored(bad, candidate(true, false), FACTS))
                    .as(bad).isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void alternatives가_문자열_배열이_아니면_거부한다() {
        for (String alts : new String[] { "[1,2]", "[{\"a\":1}]", "\"text\"", "{}", "[null]", "[]",
                "[\"가\",\"나\",\"다\",\"라\"]", "[\"  \"]" }) {
            assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", alts, "검토할 수 있습니다."),
                    candidate(true, false), FACTS)).as(alts).isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void 필수_키가_없거나_타입이_다르면_거부한다() {
        Candidate c = candidate(true, false);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm("{\"alternatives\":" + OK_ALT + "}", c, FACTS))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("21", OK_ALT, "ok"), c, FACTS))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(
                "{\"proposedClosingTime\":null,\"alternatives\":" + OK_ALT + ",\"integratedSuggestion\":5}", c, FACTS))
                .isInstanceOf(InvalidPayloadException.class);
        // 저장 형태는 분(정수)이어야 한다
        assertThatThrownBy(() -> ImprovementPayload.fromStored(
                "{\"proposedCloseMin\":\"1260\",\"alternatives\":" + OK_ALT + ",\"integratedSuggestion\":\"x\"}",
                c, FACTS)).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 제안_종료가_현재_구간에_맞지_않으면_거부한다() {
        Candidate c = candidate(true, false);
        for (String close : new String[] { "\"17:00\"", "\"18:00\"", "\"20:00\"", "\"24:30\"", "\"25:00\"",
                "\"9:00\"", "\"21:60\"", "\"abc\"" }) {
            assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm(close, OK_ALT, "검토합니다."), c, FACTS))
                    .as(close).isInstanceOf(InvalidPayloadException.class);
        }
        // 24:00까지는 허용
        assertThat(ImprovementPayload.fromLlm(llm("\"24:00\"", OK_ALT, "검토합니다."), c, FACTS)
                .proposedCloseMin()).isEqualTo(1440);
    }

    @Test
    void 종료_연장으로_해결할_수_없는데_제안_종료가_있으면_거부한다() {
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "검토합니다."),
                candidate(false, false), FACTS)).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 원천에_없는_숫자가_있으면_거부한다() {
        Candidate c = candidate(true, false);
        for (String text : new String[] { "이용자가 30% 늘 것으로 기대됩니다.", "정류장 4개가 있습니다.",
                "예산 500만원이 필요합니다.", "22:30까지도 가능합니다." }) {
            assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, text), c, FACTS))
                    .as(text).isInstanceOf(InvalidPayloadException.class);
            assertThatThrownBy(() -> ImprovementPayload.fromLlm(
                    llm("\"21:00\"", "[\"" + text + "\"]", "검토합니다."), c, FACTS))
                    .as(text).isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void 입력에_있는_시각의_숫자는_허용한다() {
        ImprovementPayload.fromLlm(llm("\"21:00\"", "[\"09:00에 열고 18:00에 닫는 현재 운영을 19시, 21시까지로 늘립니다.\"]",
                "검토합니다."), candidate(true, false), FACTS);
    }

    @Test
    void 예약제_시설은_통합_제안에_예약이_있어야_한다() {
        Candidate reservation = candidate(true, true);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "연장을 검토합니다."),
                reservation, FACTS)).isInstanceOf(InvalidPayloadException.class);
        assertThat(ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "연장을 검토합니다. 예약 필요 시설입니다."),
                reservation, FACTS)).isNotNull();
    }

    @Test
    void 너무_긴_문장은_거부한다() {
        String longText = "가".repeat(ImprovementPayload.MAX_SUGGESTION_LENGTH + 1);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, longText),
                candidate(true, false), FACTS)).isInstanceOf(InvalidPayloadException.class);
    }
}
