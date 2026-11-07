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

        // 요약에 원천(facts)에 없는 숫자가 없는지
        Set<Integer> allowed = numbersIn(factsJson);
        if (proposedCloseMin != null) {
            allowed.add(proposedCloseMin / 60);
            allowed.add(proposedCloseMin % 60);
        }
        List<String> texts = new ArrayList<>(alternatives);
        texts.add(integratedSuggestion);
        for (String t : texts) {
            for (Integer n : numbersIn(t)) {
                if (!allowed.contains(n)) {
                    throw new InvalidPayloadException("원천에 없는 숫자: " + n);
                }
            }
        }
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
