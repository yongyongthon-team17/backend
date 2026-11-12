package com.jigeumgayong.backend.analysis.service;

import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ai_analysis.payload(IMPROVEMENT) 내용. 시설 한 곳의 공간별 제안과 시설 종합 문장:
 * {"spaces":[{"spaceType":"GENERAL","proposedCloseMin":1260|null,"alternatives":[{"text":"..","basedOn":["O1"]}]}],
 *  "integratedSuggestion":"..","integratedBasedOn":["O1"]}
 * LLM 응답과 캐시 HIT 재검증이 같은 검증 규칙을 쓴다(R09). 검증 실패는 InvalidPayloadException.
 */
record ImprovementPayload(List<SpaceProposal> spaces, String integratedSuggestion, List<String> integratedBasedOn) {

    record SpaceProposal(String spaceType, Integer proposedCloseMin, List<Alternative> alternatives) {
    }

    /** 제안 한 건과 그 근거가 되는 관찰 id */
    record Alternative(String text, List<String> basedOn) {
    }

    /**
     * 검증에 쓰는 현재 사실.
     *
     * @param spaces         분석 대상 공간들
     * @param observationIds 입력에 넣은 관찰 id (basedOn은 이 안에 있어야 한다)
     * @param factsJson      LLM에 넘긴 사실 JSON (허용 숫자 계산에 사용)
     */
    record Context(List<Candidate> spaces, Set<String> observationIds, String factsJson) {
    }

    static final int MAX_ALTERNATIVES_PER_SPACE = 3;
    static final int MAX_ALTERNATIVE_LENGTH = 120;
    static final int MAX_SUGGESTION_LENGTH = 200;

    private static final Pattern HHMM = Pattern.compile("^(\\d{2}):(\\d{2})$");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    /** 단위가 붙은 숫자: 그룹1=숫자, 그룹2=개(월 제외)·곳·m·미터 */
    private static final Pattern UNIT_NUMBER =
            Pattern.compile("(?<!\\d)(\\d{1,9})(?!\\d)\\s*(개(?!월)|곳|미터|m(?![a-zA-Z]))");
    /** 정류장 수만으로는 알 수 없는 값(공백 제거 후 비교). 일반 단어 "도착"은 막지 않는다. */
    private static final List<String> BANNED_TRANSPORT_TERMS =
            List.of("배차", "막차", "첫차", "운행간격", "도착시간", "분간격");

    /** LLM 출력: 제안 종료가 "HH:mm" 문자열 또는 null */
    static ImprovementPayload fromLlm(String json, Context ctx) {
        return parse(json, ctx, true);
    }

    /** 저장된 payload: 제안 종료가 분(정수) 또는 null */
    static ImprovementPayload fromStored(String json, Context ctx) {
        return parse(json, ctx, false);
    }

    /** DB에 넣을 JSON 객체 문자열 */
    String toJson() {
        return ImprovementPrompt.MAPPER.writeValueAsString(this);
    }

    /** 해당 공간의 제안. 제안이 없는 공간이면 null */
    SpaceProposal proposalOf(String spaceType) {
        return spaces.stream().filter(s -> s.spaceType().equals(spaceType)).findFirst().orElse(null);
    }

    private static ImprovementPayload parse(String json, Context ctx, boolean fromLlm) {
        JsonNode root = parseObject(json);
        JsonNode spacesNode = root.get("spaces");
        if (spacesNode == null || !spacesNode.isArray()) {
            throw new InvalidPayloadException("spaces must be an array");
        }
        List<SpaceProposal> spaces = new ArrayList<>();
        for (JsonNode s : spacesNode) {
            if (!s.isObject() || s.get("spaceType") == null || !s.get("spaceType").isString()) {
                throw new InvalidPayloadException("space entry must have a spaceType string");
            }
            Integer closeMin = closeMinOf(s, fromLlm);
            JsonNode alts = s.get("alternatives");
            if (alts == null || !alts.isArray()) {
                throw new InvalidPayloadException("alternatives must be an array");
            }
            List<Alternative> alternatives = new ArrayList<>();
            for (JsonNode a : alts) {
                alternatives.add(new Alternative(stringOf(a, "text"), stringList(a.get("basedOn"), "basedOn")));
            }
            spaces.add(new SpaceProposal(s.get("spaceType").asString(), closeMin, List.copyOf(alternatives)));
        }
        String suggestion = stringOf(root, "integratedSuggestion");
        List<String> integratedBasedOn = stringList(root.get("integratedBasedOn"), "integratedBasedOn");

        ImprovementPayload payload = new ImprovementPayload(List.copyOf(spaces), suggestion,
                List.copyOf(integratedBasedOn));
        payload.validate(ctx);
        return payload;
    }

    private static Integer closeMinOf(JsonNode space, boolean fromLlm) {
        JsonNode close = space.get(fromLlm ? "proposedClosingTime" : "proposedCloseMin");
        if (close == null) {
            throw new InvalidPayloadException("proposed close missing");
        }
        if (close.isNull()) {
            return null;
        }
        if (fromLlm) {
            if (!close.isString()) {
                throw new InvalidPayloadException("proposedClosingTime must be string or null");
            }
            return parseHhmm(close.asString());
        }
        if (!close.isInt()) {
            throw new InvalidPayloadException("proposedCloseMin must be integer or null");
        }
        return close.asInt();
    }

    private static String stringOf(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isString()) {
            throw new InvalidPayloadException(field + " must be a string");
        }
        return v.asString().trim();
    }

    /** 문자열 배열만 허용 (객체·숫자 배열은 거부) */
    private static List<String> stringList(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new InvalidPayloadException(field + " must be an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : node) {
            if (!v.isString()) {
                throw new InvalidPayloadException(field + " must contain only strings");
            }
            out.add(v.asString().trim());
        }
        return out;
    }

    private static JsonNode parseObject(String json) {
        JsonNode root;
        try {
            root = ImprovementPrompt.MAPPER.readTree(json);
        } catch (RuntimeException e) {
            throw new InvalidPayloadException("not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidPayloadException("payload must be a JSON object");
        }
        return root;
    }

    private void validate(Context ctx) {
        // 공간: 입력에 있는 공간만, 중복 없이
        Set<String> seen = new HashSet<>();
        int totalAlternatives = 0;
        for (SpaceProposal sp : spaces) {
            Candidate c = ctx.spaces().stream().filter(x -> x.spaceType().name().equals(sp.spaceType()))
                    .findFirst().orElseThrow(() -> new InvalidPayloadException("입력에 없는 공간: " + sp.spaceType()));
            if (!seen.add(sp.spaceType())) {
                throw new InvalidPayloadException("공간이 중복됨: " + sp.spaceType());
            }
            if (sp.alternatives().size() > MAX_ALTERNATIVES_PER_SPACE) {
                throw new InvalidPayloadException("alternatives must have 0.." + MAX_ALTERNATIVES_PER_SPACE + " items");
            }
            for (Alternative a : sp.alternatives()) {
                if (a.text().isEmpty() || a.text().length() > MAX_ALTERNATIVE_LENGTH) {
                    throw new InvalidPayloadException("alternative is blank or too long");
                }
                checkBasis(a.basedOn(), ctx);
            }
            totalAlternatives += sp.alternatives().size();

            // 제안 종료는 연장으로 해결되는 공간에서만, 현재 구간에 맞게
            Integer close = sp.proposedCloseMin();
            if (close != null) {
                if (!c.extendable()) {
                    throw new InvalidPayloadException("종료 연장으로 해결할 수 없는데 proposedClosingTime이 있음");
                }
                if (close <= c.currentCloseMin() || close > 1440 || close < c.endMin()) {
                    throw new InvalidPayloadException("proposedClosingTime이 현재 구간에 맞지 않음");
                }
            }
        }
        if (totalAlternatives < 1) {
            throw new InvalidPayloadException("alternatives must have at least 1 item");
        }
        if (integratedSuggestion.isEmpty() || integratedSuggestion.length() > MAX_SUGGESTION_LENGTH) {
            throw new InvalidPayloadException("integratedSuggestion is blank or too long");
        }
        checkBasis(integratedBasedOn, ctx);
        if (ctx.spaces().get(0).requiresReservation() && !integratedSuggestion.contains("예약")) {
            throw new InvalidPayloadException("예약제 시설인데 예약 필요 안내가 없음");
        }

        // 요약에 원천(facts)에 없는 숫자가 없는지.
        // 정류장 수·반경은 단위가 붙은 형태(N개/N곳, Nm)로만 허용하고, 그 밖의 숫자는 정류장 수와 같아도 허용하지 않는다.
        JsonNode facts = parseFacts(ctx.factsJson());
        Integer stopCount = intOrNull(facts, "busStopCount");
        Integer radius = intOrNull(facts, "busStopRadiusMeters");
        Set<Integer> allowed = numbersIn(factsForNumbers(facts));
        for (SpaceProposal sp : spaces) {
            if (sp.proposedCloseMin() != null) {
                allowed.add(sp.proposedCloseMin() / 60);
                allowed.add(sp.proposedCloseMin() % 60);
            }
        }
        List<String> texts = new ArrayList<>();
        spaces.forEach(sp -> sp.alternatives().forEach(a -> texts.add(a.text())));
        texts.add(integratedSuggestion);
        for (String t : texts) {
            checkTransportText(t, stopCount, radius);
            for (Integer n : numbersIn(UNIT_NUMBER.matcher(t).replaceAll(" "))) {
                if (!allowed.contains(n)) {
                    throw new InvalidPayloadException("원천에 없는 숫자: " + n);
                }
            }
        }
    }

    /** 근거 id는 1개 이상이고 모두 입력 관찰에 있어야 한다 */
    private static void checkBasis(List<String> basedOn, Context ctx) {
        if (basedOn.isEmpty()) {
            throw new InvalidPayloadException("근거(basedOn)가 비어 있음");
        }
        for (String id : basedOn) {
            if (!ctx.observationIds().contains(id)) {
                throw new InvalidPayloadException("입력에 없는 근거 id: " + id);
            }
        }
    }

    /** 단위 있는 교통 숫자와 금지 표현(T30) 검사 */
    private static void checkTransportText(String text, Integer stopCount, Integer radius) {
        String compact = text.replaceAll("\\s+", "");
        for (String banned : BANNED_TRANSPORT_TERMS) {
            if (compact.contains(banned)) {
                throw new InvalidPayloadException("정류장 수로 알 수 없는 교통 표현: " + banned);
            }
        }
        if (stopCount == null && text.contains("정류장")) {
            throw new InvalidPayloadException("정류장 수가 입력에 없는데 정류장을 언급함");
        }
        Matcher m = UNIT_NUMBER.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            Integer expected = isCountUnit(m.group(2)) ? stopCount : radius;
            if (expected == null || n != expected) {
                throw new InvalidPayloadException("원천과 다른 정류장 관련 숫자: " + m.group().trim());
            }
        }
    }

    /**
     * integratedSuggestion에 "정류장"과 정류장 수(N개·N곳)가 함께 들어 있는지. 검증을 통과한 payload에서만 호출한다.
     * 숫자는 다시 비교하지 않는다: validate()가 문장 속 N개·N곳을 busStopCount와 같게 강제하기 때문이다.
     */
    boolean suggestionMentionsStopCount() {
        if (!integratedSuggestion.contains("정류장")) {
            return false;
        }
        Matcher m = UNIT_NUMBER.matcher(integratedSuggestion);
        while (m.find()) {
            if (isCountUnit(m.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCountUnit(String unit) {
        return unit.startsWith("개") || unit.equals("곳");
    }

    private static JsonNode parseFacts(String factsJson) {
        try {
            return ImprovementPrompt.MAPPER.readTree(factsJson);
        } catch (RuntimeException e) {
            throw new InvalidPayloadException("facts is not valid JSON");
        }
    }

    private static Integer intOrNull(JsonNode facts, String field) {
        JsonNode v = facts.get(field);
        return v != null && v.isInt() ? v.asInt() : null;
    }

    /** 허용 숫자 집합은 시각·구간 등 교통 외 facts에서만 만든다. 관찰 id(O1…)의 숫자는 허용 숫자로 치지 않는다. */
    private static String factsForNumbers(JsonNode facts) {
        ObjectNode copy = ((ObjectNode) facts).deepCopy();
        copy.remove("busStopCount");
        copy.remove("busStopRadiusMeters");
        JsonNode observations = copy.get("observations");
        if (observations != null && observations.isArray()) {
            for (JsonNode o : observations) {
                ((ObjectNode) o).remove("id");
            }
        }
        return copy.toString();
    }

    private static Set<Integer> numbersIn(String text) {
        Set<Integer> numbers = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String digits = m.group();
            if (digits.length() > 9) {
                numbers.add(Integer.MAX_VALUE); // 비정상적으로 긴 숫자는 항상 허용 목록 밖
            } else {
                numbers.add(Integer.parseInt(digits));
            }
        }
        return numbers;
    }

    /** "HH:mm"(24:00 허용) → 분 */
    private static int parseHhmm(String text) {
        Matcher m = HHMM.matcher(text);
        if (!m.matches()) {
            throw new InvalidPayloadException("proposedClosingTime must be HH:mm");
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (min > 59 || h > 24 || (h == 24 && min != 0)) {
            throw new InvalidPayloadException("proposedClosingTime out of range");
        }
        return h * 60 + min;
    }
}
