package com.jigeumgayong.backend.analysis.service;

import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * ai_analysis.payload(IMPROVEMENT) 내용: {"proposedCloseMin":1260|null,"alternatives":[...],"integratedSuggestion":"..."}
 * LLM 응답과 캐시 HIT 재검증이 같은 검증 규칙을 쓴다(R09). 검증 실패는 InvalidPayloadException.
 */
record ImprovementPayload(Integer proposedCloseMin, List<String> alternatives, String integratedSuggestion) {

    static final int MAX_ALTERNATIVES = 3;
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
    static ImprovementPayload fromLlm(String json, Candidate c, String factsJson) {
        JsonNode root = parseObject(json);
        JsonNode close = root.get("proposedClosingTime");
        if (close == null) {
            throw new InvalidPayloadException("proposedClosingTime missing");
        }
        Integer closeMin = null;
        if (!close.isNull()) {
            if (!close.isString()) {
                throw new InvalidPayloadException("proposedClosingTime must be string or null");
            }
            closeMin = parseHhmm(close.asString());
        }
        return build(closeMin, root, c, factsJson);
    }

    /** 저장된 payload: 제안 종료가 분(정수) 또는 null */
    static ImprovementPayload fromStored(String json, Candidate c, String factsJson) {
        JsonNode root = parseObject(json);
        JsonNode close = root.get("proposedCloseMin");
        if (close == null) {
            throw new InvalidPayloadException("proposedCloseMin missing");
        }
        Integer closeMin = null;
        if (!close.isNull()) {
            if (!close.isInt()) {
                throw new InvalidPayloadException("proposedCloseMin must be integer or null");
            }
            closeMin = close.asInt();
        }
        return build(closeMin, root, c, factsJson);
    }

    /** DB에 넣을 JSON 객체 문자열 */
    String toJson() {
        return ImprovementPrompt.MAPPER.writeValueAsString(this);
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

    private static ImprovementPayload build(Integer closeMin, JsonNode root, Candidate c, String factsJson) {
        // alternatives: string[] 만 허용 (객체·숫자 배열·문자열 아님은 거부)
        JsonNode alts = root.get("alternatives");
        if (alts == null || !alts.isArray()) {
            throw new InvalidPayloadException("alternatives must be an array");
        }
        List<String> alternatives = new ArrayList<>();
        for (JsonNode a : alts) {
            if (!a.isString()) {
                throw new InvalidPayloadException("alternatives must contain only strings");
            }
            alternatives.add(a.asString().trim());
        }
        JsonNode text = root.get("integratedSuggestion");
        if (text == null || !text.isString()) {
            throw new InvalidPayloadException("integratedSuggestion must be a string");
        }
        String suggestion = text.asString().trim();

        ImprovementPayload payload = new ImprovementPayload(closeMin, List.copyOf(alternatives), suggestion);
        payload.validate(c, factsJson);
        return payload;
    }

    private void validate(Candidate c, String factsJson) {
        if (alternatives.isEmpty() || alternatives.size() > MAX_ALTERNATIVES) {
            throw new InvalidPayloadException("alternatives must have 1.." + MAX_ALTERNATIVES + " items");
        }
        for (String a : alternatives) {
            if (a.isEmpty() || a.length() > MAX_ALTERNATIVE_LENGTH) {
                throw new InvalidPayloadException("alternative is blank or too long");
            }
        }
        if (integratedSuggestion.isEmpty() || integratedSuggestion.length() > MAX_SUGGESTION_LENGTH) {
            throw new InvalidPayloadException("integratedSuggestion is blank or too long");
        }

        // suggested_close가 현재 구간에 맞는지
        if (proposedCloseMin != null) {
            if (!c.extendable()) {
                throw new InvalidPayloadException("종료 연장으로 해결할 수 없는데 proposedClosingTime이 있음");
            }
            if (proposedCloseMin <= c.currentCloseMin() || proposedCloseMin > 1440 || proposedCloseMin < c.endMin()) {
                throw new InvalidPayloadException("proposedClosingTime이 현재 구간에 맞지 않음");
            }
        }
        if (c.requiresReservation() && !integratedSuggestion.contains("예약")) {
            throw new InvalidPayloadException("예약제 시설인데 예약 필요 안내가 없음");
        }

        // 요약에 원천(facts)에 없는 숫자가 없는지.
        // 정류장 수·반경은 단위가 붙은 형태(N개/N곳, Nm)로만 허용하고, 그 밖의 숫자는 정류장 수와 같아도 허용하지 않는다.
        JsonNode facts = parseFacts(factsJson);
        Integer stopCount = intOrNull(facts, "busStopCount");
        Integer radius = intOrNull(facts, "busStopRadiusMeters");
        Set<Integer> allowed = numbersIn(factsWithoutTransport(facts));
        if (proposedCloseMin != null) {
            allowed.add(proposedCloseMin / 60);
            allowed.add(proposedCloseMin % 60);
        }
        List<String> texts = new ArrayList<>(alternatives);
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
            boolean isCount = m.group(2).startsWith("개") || m.group(2).equals("곳");
            Integer expected = isCount ? stopCount : radius;
            if (expected == null || n != expected) {
                throw new InvalidPayloadException("원천과 다른 정류장 관련 숫자: " + m.group().trim());
            }
        }
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

    /** 허용 숫자 집합은 시각·구간 등 교통 외 facts에서만 만든다 */
    private static String factsWithoutTransport(JsonNode facts) {
        tools.jackson.databind.node.ObjectNode copy = ((tools.jackson.databind.node.ObjectNode) facts).deepCopy();
        copy.remove("busStopCount");
        copy.remove("busStopRadiusMeters");
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
