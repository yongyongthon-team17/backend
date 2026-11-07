package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.DayState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원천 운영시간 → 요일(0=월 … 6=일)별 구간 목록. 시각은 자정부터의 분이고 24:00은 1440 (docs/10 §6, R05·R06).
 * 해석이 애매하면 UNKNOWN으로 두고 휴관으로 추정하지 않는다.
 */
final class HourParser {

    /** 한 구간. OPEN이 아니면 openMin·closeMin은 null. */
    record Slot(DayState state, Integer openMin, Integer closeMin) {
        static final Slot CLOSED = new Slot(DayState.CLOSED, null, null);
        static final Slot UNKNOWN = new Slot(DayState.UNKNOWN, null, null);

        static Slot open(int openMin, int closeMin) {
            return new Slot(DayState.OPEN, openMin, closeMin);
        }
    }

    /** 휴관일 해석 결과. vague=true면 '둘째 주 월'처럼 요일로 고정할 수 없는 휴관이 있다는 뜻 (R07: note에만 기록). */
    record ClosedDays(Set<Integer> days, boolean vague) {
    }

    /** 작은도서관 이용시간 해석 결과. flag는 해석 품질 메모(로그용). */
    record SmallHours(Map<Integer, List<Slot>> hours, String flag) {
    }

    static final String DAYS = "월화수목금토일";

    private static final Pattern TIME = Pattern.compile("\\s*(\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*");
    private static final Pattern DAY_RANGE = Pattern.compile("([월화수목금토일])\\s*[~\\-–]\\s*([월화수목금토일])");
    private static final Pattern CLOSED_TOKEN = Pattern.compile("([월화수목금토일])(요일)?");
    private static final Pattern SPACE_NAMED = Pattern.compile("\\(.+?(자료실|열람실|실)\\)");
    private static final Pattern KOREAN_TIME = Pattern.compile("(\\d{1,2})\\s*시\\s*(\\d{1,2})?\\s*분?");
    private static final Pattern BIWEEKLY = Pattern.compile("\\d\\s*[·,]?\\s*\\d?\\s*주|[첫둘셋넷]째");
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[~\\-–]\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern CLOSED_WORD = Pattern.compile("휴관|휴무|쉼");
    private static final Pattern DAY_SPEC = Pattern.compile("(\\d)(?:-(\\d))?");

    private HourParser() {
    }

    // ------------------------------------------------------------ 기본 변환

    static Map<Integer, List<Slot>> unknownWeek() {
        return uniformWeek(Slot.UNKNOWN);
    }

    static Map<Integer, List<Slot>> uniformWeek(Slot slot) {
        Map<Integer, List<Slot>> week = new TreeMap<>();
        for (int d = 0; d < 7; d++) {
            week.put(d, List.of(slot));
        }
        return week;
    }

    /** 'HH:MM'(또는 HH:MM:SS) → 분. 형식이 틀리거나 0~1440을 벗어나면 null. */
    static Integer toMin(String t) {
        Matcher m = TIME.matcher(t == null ? "" : t);
        if (!m.matches()) {
            return null;
        }
        int v = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        return v <= 1440 ? v : null;
    }

    /**
     * 원천 시작/종료 시각 → OPEN 구간, 00:00~00:00이면 CLOSED, 결측·표현 불가(자정 넘김 등)면 null.
     * 종료만 00:00이면 자정 마감(1440)으로 본다.
     */
    static Slot interval(String start, String end) {
        Integer o = toMin(start);
        Integer c = toMin(end);
        if (o == null || c == null) {
            return null;
        }
        if (o == 0 && c == 0) {
            return Slot.CLOSED;
        }
        if (c == 0) {
            c = 1440;
        }
        if (!(o <= 1439 && c >= 1 && c <= 1440 && o < c)) {
            return null;
        }
        return Slot.open(o, c);
    }

    /** OPEN 구간의 합집합. 겹치거나 맞닿으면 하나로 합친다. */
    static List<Slot> mergeIntervals(List<Slot> slots) {
        List<Slot> sorted = slots.stream()
                .filter(s -> s.state() == DayState.OPEN)
                .sorted(Comparator.comparing(Slot::openMin))
                .toList();
        List<int[]> merged = new ArrayList<>();
        for (Slot s : sorted) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && s.openMin() <= last[1]) {
                last[1] = Math.max(last[1], s.closeMin());
            } else {
                merged.add(new int[] {s.openMin(), s.closeMin()});
            }
        }
        return merged.stream().map(m -> Slot.open(m[0], m[1])).toList();
    }

    // ------------------------------------------------------------ 휴관일

    /** 휴관일 문자열 → 매주 고정 휴관 요일. '둘째·넷째 월요일' 같은 격주 휴관은 요일에 넣지 않는다 (R07). */
    static ClosedDays parseClosedDays(String text) {
        Set<Integer> days = new HashSet<>();
        boolean vague = false;
        String t = EtlUtil.strip(text);
        if (t.isEmpty() || t.equals("연중무휴") || t.equals("없음") || t.equals("무휴")) {
            return new ClosedDays(days, false);
        }
        // 공백으로 쪼개면 '둘째 주 월'의 '월'이 따로 읽히므로 구분자는 + , / · 만 쓴다
        for (String raw : t.split("[+,/·]+")) {
            String tok = raw.strip();
            if (tok.isEmpty()) {
                continue;
            }
            if (tok.contains("휴일")) {
                continue; // 공휴일 규칙은 컬럼이 없어 표시용 note로만 남는다
            }
            if (tok.contains("주") || tok.contains("째")) {
                vague = true;
                continue;
            }
            Matcher m = CLOSED_TOKEN.matcher(tok);
            if (m.matches()) {
                days.add(DAYS.indexOf(m.group(1)));
            } else {
                vague = true;
            }
        }
        return new ClosedDays(days, vague);
    }

    /** 평일(월~금)·토·일 구간을 7일로 풀어 쓴다. 휴관 요일은 CLOSED, 구간이 없으면 UNKNOWN. */
    static Map<Integer, List<Slot>> weekFrom(List<Slot> weekday, List<Slot> sat, List<Slot> sun, Set<Integer> closed) {
        Map<Integer, List<Slot>> hours = new TreeMap<>();
        for (int d = 0; d < 7; d++) {
            List<Slot> iv = d < 5 ? weekday : (d == 5 ? sat : sun);
            if (closed.contains(d)) {
                hours.put(d, List.of(Slot.CLOSED));
            } else if (iv == null || iv.isEmpty()) {
                hours.put(d, List.of(Slot.UNKNOWN));
            } else {
                hours.put(d, iv);
            }
        }
        return hours;
    }

    // ------------------------------------------------------------ 작은도서관 이용시간 자유 텍스트

    /** '월~금', '토,일', '화·목', '평일', '주말' → 요일 번호 집합 */
    private static Set<Integer> daysIn(String text) {
        String t = text.replace("공휴일", "").replace("요일", "").replace("평일", "월~금").replace("주말", "토~일");
        Set<Integer> out = new TreeSet<>();
        Matcher m = DAY_RANGE.matcher(t);
        while (m.find()) {
            int i = DAYS.indexOf(m.group(1));
            int j = DAYS.indexOf(m.group(2));
            if (i <= j) {
                for (int d = i; d <= j; d++) {
                    out.add(d);
                }
            } else { // '일-금'처럼 주말을 가로지르는 범위
                for (int d = i; d < 7; d++) {
                    out.add(d);
                }
                for (int d = 0; d <= j; d++) {
                    out.add(d);
                }
            }
        }
        String rest = DAY_RANGE.matcher(t).replaceAll("");
        for (char ch : rest.toCharArray()) {
            int idx = DAYS.indexOf(ch);
            if (idx >= 0) {
                out.add(idx);
            }
        }
        return out;
    }

    /** 이용시간 문자열을 최대한 읽고, 애매하면 UNKNOWN으로 둔다. 격주·공간별 시간은 자동 해석하지 않는다. */
    static SmallHours parseSmall(String text, String closedFlag) {
        String t = EtlUtil.strip(text);
        if ("Y".equals(closedFlag) || t.contains("휴관중")) {
            return new SmallHours(uniformWeek(Slot.CLOSED), "휴관 중");
        }
        if (t.isEmpty()) {
            return new SmallHours(unknownWeek(), "이용시간 없음");
        }
        if (SPACE_NAMED.matcher(t).find()) {
            return new SmallHours(unknownWeek(), "공간별 시간이 달라 자동 해석 안 함");
        }
        // '9시 30분' → '09:30'
        t = KOREAN_TIME.matcher(t).replaceAll(m -> String.format("%02d:%02d",
                Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2))));

        Map<Integer, List<Slot>> hours = new TreeMap<>();
        boolean partial = false;
        for (String raw : t.split("/")) {
            String seg = raw.strip();
            if (seg.isEmpty()) {
                continue;
            }
            if (BIWEEKLY.matcher(seg).find()) {
                partial = true; // 격주 → 요일 고정 불가
                continue;
            }
            List<Slot> ivs = new ArrayList<>();
            boolean hasTimes = false;
            boolean bad = false;
            Matcher tm = TIME_RANGE.matcher(seg);
            while (tm.find()) {
                hasTimes = true;
                Slot iv = interval(tm.group(1) + ":" + tm.group(2), tm.group(3) + ":" + tm.group(4));
                if (iv == null || iv.state() != DayState.OPEN) {
                    bad = true;
                    break;
                }
                ivs.add(iv);
            }
            String head = hasTimes ? seg.split("\\d{1,2}:\\d{2}", -1)[0] : seg;
            Set<Integer> days = daysIn(head);
            if (days.isEmpty()) {
                partial = true;
                continue;
            }
            if (hasTimes) {
                if (bad || ivs.isEmpty()) {
                    partial = true;
                    continue;
                }
                List<Slot> merged = mergeIntervals(ivs);
                for (int d : days) {
                    hours.put(d, merged);
                }
            } else if (CLOSED_WORD.matcher(seg).find()) {
                for (int d : days) {
                    hours.put(d, List.of(Slot.CLOSED));
                }
            } else {
                partial = true;
            }
        }
        for (int d = 0; d < 7; d++) {
            hours.putIfAbsent(d, List.of(Slot.UNKNOWN));
        }
        boolean allUnknown = hours.values().stream().allMatch(h -> h.get(0).state() == DayState.UNKNOWN);
        if (allUnknown) {
            return new SmallHours(hours, "파싱 실패");
        }
        boolean anyUnknown = hours.values().stream().anyMatch(h -> h.get(0).state() == DayState.UNKNOWN);
        return new SmallHours(hours, partial || anyUnknown ? "일부 요일 미기재/격주" : "");
    }

    // ------------------------------------------------------------ 수기 입력 hours 문자열

    /**
     * manual_facilities.csv의 hours 컬럼. 예: "0-4 09:00-18:00; 5-6 CLOSED", "1-3 09:00-12:00,13:00-18:00".
     * 빈 값·UNKNOWN은 전 요일 UNKNOWN, ALWAYS는 운영시간 행 없음(null, access_type=ALWAYS_OPEN용).
     *
     * @throws IllegalArgumentException 형식이 틀리거나 시간이 유효하지 않을 때
     */
    static Map<Integer, List<Slot>> parseSpec(String specText) {
        String spec = EtlUtil.strip(specText);
        if (spec.isEmpty() || spec.equals("UNKNOWN")) {
            return unknownWeek();
        }
        if (spec.equals("ALWAYS")) {
            return null;
        }
        Map<Integer, List<Slot>> out = new TreeMap<>();
        for (String raw : spec.split(";")) {
            String seg = raw.strip();
            if (seg.isEmpty()) {
                continue;
            }
            String[] parts = seg.split("\\s+", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("요일 형식 오류: " + seg);
            }
            List<Integer> days = new ArrayList<>();
            for (String part : parts[0].split(",")) {
                Matcher m = DAY_SPEC.matcher(part);
                if (!m.matches()) {
                    throw new IllegalArgumentException("요일 형식 오류: " + seg);
                }
                int a = Integer.parseInt(m.group(1));
                int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
                if (a > b || b > 6) {
                    throw new IllegalArgumentException("요일 범위 오류: " + seg);
                }
                for (int d = a; d <= b; d++) {
                    days.add(d);
                }
            }
            String body = parts[1].strip();
            List<Slot> slots = new ArrayList<>();
            if (body.equals("CLOSED")) {
                slots.add(Slot.CLOSED);
            } else if (body.equals("UNKNOWN")) {
                slots.add(Slot.UNKNOWN);
            } else {
                for (String iv : body.split(",")) {
                    String[] oc = iv.strip().split("-");
                    if (oc.length != 2) {
                        throw new IllegalArgumentException("시간 형식 오류: " + seg);
                    }
                    int o = hhmm(oc[0], seg);
                    int c = hhmm(oc[1], seg);
                    if (!(o >= 0 && o <= 1439 && c >= 1 && c <= 1440 && o < c)) {
                        throw new IllegalArgumentException("시간 오류: " + seg);
                    }
                    slots.add(Slot.open(o, c));
                }
            }
            for (int d : days) {
                out.put(d, slots);
            }
        }
        for (int d = 0; d < 7; d++) {
            out.putIfAbsent(d, List.of(Slot.UNKNOWN));
        }
        return out;
    }

    private static int hhmm(String text, String seg) {
        String[] hm = text.strip().split(":");
        if (hm.length != 2) {
            throw new IllegalArgumentException("시간 형식 오류: " + seg);
        }
        try {
            return Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("시간 형식 오류: " + seg);
        }
    }
}
