package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.analysis.service.ImprovementPayload.Context;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ImprovementPayloadTest {

    /** 현재 09:00-18:00(1080), 희망 19:00-21:00, 종료 연장으로 해결 가능 */
    static Candidate candidate(SpaceType type, boolean extendable, boolean reservation) {
        return new Candidate(null, type, "종합자료실", null, null, reservation, 1080, extendable, 1, 1140, 1260);
    }

    static Context ctx(boolean extendable, boolean reservation, String facts) {
        return new Context(List.of(candidate(SpaceType.GENERAL, extendable, reservation)), Set.of("O1", "O2"), facts);
    }

    static final String FACTS = "{\"spaces\":[{\"spaceLabel\":\"종합자료실\",\"currentClosingTime\":\"18:00\","
            + "\"openIntervals\":[{\"openTime\":\"09:00\",\"closeTime\":\"18:00\"}]}],"
            + "\"requestStart\":\"19:00\",\"requestEnd\":\"21:00\","
            + "\"observations\":[{\"id\":\"O1\",\"type\":\"REQUEST_GAP\",\"detail\":\"화요일 19:00~21:00 미운영\"}]}";

    /** 정류장 4개가 AVAILABLE로 들어간 facts */
    static final String FACTS_STOPS = FACTS.substring(0, FACTS.length() - 1)
            + ",\"busStopCount\":4,\"busStopRadiusMeters\":500}";

    static final String OK_ALT = "종합자료실을 21:00까지 시범 운영하는 방안을 검토합니다.";

    private static String json(String s) {
        return "\"" + s + "\"";
    }

    /** close는 JSON 값 그대로("\"21:00\"" 또는 "null"), basedOn은 O1 */
    static String llm(String close, String suggestion, String... texts) {
        String alts = java.util.Arrays.stream(texts)
                .map(t -> "{\"text\":" + json(t) + ",\"basedOn\":[\"O1\"]}").collect(Collectors.joining(","));
        return "{\"spaces\":[{\"spaceType\":\"GENERAL\",\"proposedClosingTime\":" + close + ",\"alternatives\":["
                + alts + "]}],\"integratedSuggestion\":" + json(suggestion) + ",\"integratedBasedOn\":[\"O1\"]}";
    }

    private static ImprovementPayload ok(String close, String suggestion, String... texts) {
        return ImprovementPayload.fromLlm(llm(close, suggestion, texts), ctx(true, false, FACTS));
    }

    @Test
    void 정상_LLM_응답은_분_단위_payload가_된다() {
        ImprovementPayload p = ok("\"21:00\"", "21시까지 연장을 검토할 수 있습니다.", OK_ALT);

        assertThat(p.spaces()).hasSize(1);
        assertThat(p.proposalOf("GENERAL").proposedCloseMin()).isEqualTo(1260);
        assertThat(p.proposalOf("GENERAL").alternatives().get(0).basedOn()).containsExactly("O1");
        assertThat(p.toJson()).startsWith("{").contains("\"proposedCloseMin\":1260");
    }

    @Test
    void 저장_형태로_왕복한다() {
        ImprovementPayload p = ok("\"21:00\"", "검토할 수 있습니다.", OK_ALT);

        ImprovementPayload stored = ImprovementPayload.fromStored(p.toJson(), ctx(true, false, FACTS));

        assertThat(stored).isEqualTo(p);
    }

    @Test
    void 제안_종료가_null이어도_통과한다() {
        assertThat(ok("null", "검토할 수 있습니다.", OK_ALT).proposalOf("GENERAL").proposedCloseMin()).isNull();
    }

    @Test
    void 제안이_없는_공간은_proposalOf가_null이다() {
        assertThat(ok("null", "검토할 수 있습니다.", OK_ALT).proposalOf("READING")).isNull();
    }

    @Test
    void 손상_JSON과_객체가_아닌_JSON은_거부한다() {
        for (String bad : new String[] { "not json", "[]", "\"x\"", "42", "" }) {
            assertThatThrownBy(() -> ImprovementPayload.fromStored(bad, ctx(true, false, FACTS)))
                    .as(bad).isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void 제안_개수와_문구_조건을_어기면_거부한다() {
        Context c = ctx(true, false, FACTS);
        // 전체 제안 0개, 공간당 4개, 빈 문구, 너무 긴 문구
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("null", "검토합니다."), c))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("null", "검토합니다.", "가", "나", "다", "라"), c))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("null", "검토합니다.", "  "), c))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(
                llm("null", "검토합니다.", "가".repeat(ImprovementPayload.MAX_ALTERNATIVE_LENGTH + 1)), c))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 필수_키가_없거나_타입이_다르면_거부한다() {
        Context c = ctx(true, false, FACTS);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm("{\"integratedSuggestion\":\"x\"}", c))
                .isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("21", "ok", OK_ALT), c))
                .isInstanceOf(InvalidPayloadException.class);
        // alternatives가 문자열 배열(옛 형태)이면 거부
        assertThatThrownBy(() -> ImprovementPayload.fromLlm("{\"spaces\":[{\"spaceType\":\"GENERAL\","
                + "\"proposedClosingTime\":null,\"alternatives\":[\"문자열\"]}],\"integratedSuggestion\":\"x\","
                + "\"integratedBasedOn\":[\"O1\"]}", c)).isInstanceOf(InvalidPayloadException.class);
        // 저장 형태는 분(정수)이어야 한다
        assertThatThrownBy(() -> ImprovementPayload.fromStored("{\"spaces\":[{\"spaceType\":\"GENERAL\","
                + "\"proposedCloseMin\":\"1260\",\"alternatives\":[{\"text\":\"x\",\"basedOn\":[\"O1\"]}]}],"
                + "\"integratedSuggestion\":\"x\",\"integratedBasedOn\":[\"O1\"]}", c))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 근거_id가_없거나_입력에_없으면_거부한다() {
        Context c = ctx(true, false, FACTS);
        String noBasis = "{\"spaces\":[{\"spaceType\":\"GENERAL\",\"proposedClosingTime\":null,\"alternatives\":"
                + "[{\"text\":\"검토합니다.\",\"basedOn\":[]}]}],\"integratedSuggestion\":\"x\",\"integratedBasedOn\":[\"O1\"]}";
        String unknownBasis = llm("null", "x", OK_ALT).replace("\"basedOn\":[\"O1\"]", "\"basedOn\":[\"O9\"]");
        String noIntegratedBasis = llm("null", "x", OK_ALT).replace("\"integratedBasedOn\":[\"O1\"]",
                "\"integratedBasedOn\":[]");
        for (String bad : new String[] { noBasis, unknownBasis, noIntegratedBasis }) {
            assertThatThrownBy(() -> ImprovementPayload.fromLlm(bad, c)).as(bad)
                    .isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void 입력에_없는_공간이나_중복_공간은_거부한다() {
        Context c = ctx(true, false, FACTS);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(
                llm("null", "x", OK_ALT).replace("\"GENERAL\"", "\"READING\""), c))
                .isInstanceOf(InvalidPayloadException.class);
        String dup = llm("null", "x", OK_ALT);
        String space = dup.substring(dup.indexOf("{\"spaceType\""), dup.indexOf("]}],") + 2);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(dup.replace(space, space + "," + space), c))
                .isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 제안_종료가_현재_구간에_맞지_않으면_거부한다() {
        for (String close : new String[] { "\"17:00\"", "\"18:00\"", "\"20:00\"", "\"24:30\"", "\"25:00\"",
                "\"9:00\"", "\"21:60\"", "\"abc\"" }) {
            assertThatThrownBy(() -> ok(close, "검토합니다.", OK_ALT)).as(close)
                    .isInstanceOf(InvalidPayloadException.class);
        }
        // 24:00까지는 허용
        assertThat(ok("\"24:00\"", "검토합니다.", OK_ALT).proposalOf("GENERAL").proposedCloseMin()).isEqualTo(1440);
    }

    @Test
    void 종료_연장으로_해결할_수_없는데_제안_종료가_있으면_거부한다() {
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", "검토합니다.", OK_ALT),
                ctx(false, false, FACTS))).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void 원천에_없는_숫자가_있으면_거부한다() {
        for (String text : new String[] { "이용자가 30% 늘 것으로 기대됩니다.", "정류장 4개가 있습니다.",
                "예산 500만원이 필요합니다.", "22:30까지도 가능합니다." }) {
            assertThatThrownBy(() -> ok("\"21:00\"", text, OK_ALT)).as(text)
                    .isInstanceOf(InvalidPayloadException.class);
            assertThatThrownBy(() -> ok("\"21:00\"", "검토합니다.", text)).as(text)
                    .isInstanceOf(InvalidPayloadException.class);
        }
    }

    @Test
    void 관찰_id의_숫자는_허용_숫자로_치지_않는다() {
        // O1의 1은 시각·구간이 아니라 id일 뿐이다
        assertThatThrownBy(() -> ok("\"21:00\"", "검토합니다.", "주 1회 시범 운영을 검토합니다."))
                .isInstanceOf(InvalidPayloadException.class);
    }

    private static void assertRejected(String facts, String text) {
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", text, OK_ALT), ctx(true, false, facts)))
                .as(text).isInstanceOf(InvalidPayloadException.class);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", "검토합니다.", text),
                ctx(true, false, facts))).as(text).isInstanceOf(InvalidPayloadException.class);
    }

    @Test
    void AVAILABLE이면_정류장_수와_반경은_단위가_붙은_형태로만_허용한다() {
        ImprovementPayload.fromLlm(llm("\"21:00\"", "시설 500m 내 정류장은 4개이며 실제 운행은 별도 확인이 필요합니다.", OK_ALT),
                ctx(true, false, FACTS_STOPS));
        ImprovementPayload.fromLlm(llm("\"21:00\"", "정류장이 4곳 있습니다.", OK_ALT), ctx(true, false, FACTS_STOPS));
    }

    private static boolean mentions(String facts, String suggestion) {
        return ImprovementPayload.fromLlm(llm("\"21:00\"", suggestion, OK_ALT), ctx(true, false, facts))
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
        ok("\"21:00\"", "도착 후 이용 가능 여부는 별도 확인하세요.", OK_ALT);
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
        ok("\"21:00\"", "19시부터 21시까지 검토합니다.", OK_ALT);
    }

    @Test
    void 입력에_있는_시각의_숫자는_허용한다() {
        ok("\"21:00\"", "검토합니다.", "09:00에 열고 18:00에 닫는 현재 운영을 19시, 21시까지로 늘립니다.");
    }

    @Test
    void 예약제_시설은_통합_제안에_예약이_있어야_한다() {
        Context reservation = ctx(true, true, FACTS);
        assertThatThrownBy(() -> ImprovementPayload.fromLlm(llm("\"21:00\"", "연장을 검토합니다.", OK_ALT), reservation))
                .isInstanceOf(InvalidPayloadException.class);
        assertThat(ImprovementPayload.fromLlm(llm("\"21:00\"", "연장을 검토합니다. 예약 필요 시설입니다.", OK_ALT),
                reservation)).isNotNull();
    }

    @Test
    void 너무_긴_문장은_거부한다() {
        String longText = "가".repeat(ImprovementPayload.MAX_SUGGESTION_LENGTH + 1);
        assertThatThrownBy(() -> ok("\"21:00\"", longText, OK_ALT)).isInstanceOf(InvalidPayloadException.class);
    }
}
