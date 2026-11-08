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

    /** 정류장 4개가 AVAILABLE로 들어간 facts */
    static final String FACTS_STOPS = FACTS.substring(0, FACTS.length() - 1)
            + ",\"busStopCount\":4,\"busStopRadiusMeters\":500}";

    private static void assertRejected(String facts, String text) {
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, text),
                candidate(true, false), facts)).as(text).isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(
                llm("\"21:00\"", "[\"" + text + "\"]", "검토합니다."), candidate(true, false), facts))
                .as(text).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void AVAILABLE이면_정류장_수와_반경은_단위가_붙은_형태로만_허용한다() {
        ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT,
                "시설 500m 내 정류장은 4개이며 실제 운행은 별도 확인이 필요합니다."), candidate(true, false), FACTS_STOPS);
        ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "정류장이 4곳 있습니다."),
                candidate(true, false), FACTS_STOPS);
    }

    private static boolean mentions(String facts, String suggestion) {
        return ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, suggestion), candidate(true, false), facts)
                .suggestionMentionsStopCount();
    }

    @Test
    void 통합_제안에_정류장과_N개_N곳이_함께_있어야_정류장_수를_언급한_것이다() {
        assertThat(mentions(FACTS_STOPS, "시설 500m 내 정류장은 4개이며 실제 운행은 별도 확인이 필요합니다.")).isTrue();
        assertThat(mentions(FACTS_STOPS, "정류장이 4곳 있습니다.")).isTrue();
        assertThat(mentions(FACTS_STOPS, "21시까지 연장을 검토할 수 있습니다.")).isFalse();
        assertThat(mentions(FACTS_STOPS, "정류장 이용은 별도 확인이 필요합니다.")).isFalse();
        String zero = FACTS.substring(0, FACTS.length() - 1) + ",\"busStopCount\":0,\"busStopRadiusMeters\":500}";
        assertThat(mentions(zero, "시설 500m 내 정류장은 0개입니다.")).isTrue();
    }

    @Test
    void 정류장_수와_다른_N개_N곳은_거부한다() {
        for (String text : new String[] { "정류장은 5개입니다.", "정류장이 3곳 있습니다.", "반경 300m 내 정류장은 4개입니다." }) {
            assertRejected(FACTS_STOPS, text);
        }
    }

    @Test
    void 정류장_수와_같은_숫자라도_다른_의미면_거부한다() {
        // T30: 4는 정류장 수일 뿐 분·시간 같은 값이 아니다
        for (String text : new String[] { "4분 간격으로 운행합니다.", "배차 간격은 정류장 수를 고려해 검토합니다.",
                "막차는 늦게까지 있습니다.", "첫차 시간을 확인합니다.", "운행 간격이 짧습니다.", "도착 시간이 빠릅니다.",
                "정류장 4개로 4분 간격 운행이 가능합니다.", "4시에 도착합니다." }) {
            assertRejected(FACTS_STOPS, text);
        }
    }

    @Test
    void 일반_단어_도착은_막지_않는다() {
        ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "도착 후 이용 가능 여부는 별도 확인하세요."),
                candidate(true, false), FACTS);
    }

    @Test
    void 정류장_수가_입력에_없으면_정류장_언급과_N개_N곳을_거부한다() {
        for (String text : new String[] { "정류장이 가까워 이용하기 좋습니다.", "정류장 4개가 있습니다.", "500m 내에 있습니다." }) {
            assertRejected(FACTS, text);
        }
    }

    @Test
    void 개월_같은_다른_단위는_정류장_수로_보지_않는다() {
        assertRejected(FACTS, "3개월 시범 운영을 검토합니다."); // 3은 원천에 없는 숫자라 거부
        ImprovementPayload.fromLlm(llm("\"21:00\"", OK_ALT, "19시부터 21시까지 검토합니다."), candidate(true, false), FACTS);
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
