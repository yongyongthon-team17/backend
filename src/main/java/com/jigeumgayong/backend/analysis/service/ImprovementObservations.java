package com.jigeumgayong.backend.analysis.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.PeerHoursComparison;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시설의 '부족한 점'을 데이터에서 규칙대로 계산한 사실 목록(관찰). LLM은 이 목록에 있는 것만 근거로 제안한다.
 * 판단이 아니라 계산이므로 같은 데이터에서는 항상 같은 목록(같은 id)이 나와 source_hash가 안정적이다.
 * 이용 시간과 무관하게 모든 시설을 대상으로 하며, 희망 시간에 이용 불가인 경우는 REQUEST_GAP 등으로 먼저 나온다.
 */
final class ImprovementObservations {

    static final String REQUEST_GAP = "REQUEST_GAP";
    static final String CLOSED_ON_REQUEST_DAY = "CLOSED_ON_REQUEST_DAY";
    static final String HOURS_UNKNOWN = "HOURS_UNKNOWN";
    static final String REGULAR_CLOSED_DAYS = "REGULAR_CLOSED_DAYS";
    static final String DAY_VARIANCE = "DAY_VARIANCE";
    static final String RESERVATION_REQUIRED = "RESERVATION_REQUIRED";
    static final String FEW_BUS_STOPS = "FEW_BUS_STOPS";
    /** 부족한 점이 하나도 없을 때만 쓰는 대체 관찰(일반 보완 검토). 설정으로 켜야 한다 */
    static final String GENERAL_REVIEW = "GENERAL_REVIEW";

    /**
     * 부족한 점이 없는 시설용 관찰. 서버가 만든 사실이므로 LLM 응답의 근거 id 검증을 그대로 거친다.
     * detail이 LLM 입력에 들어가므로, 새로운 문제·수치를 만들지 않고 일반 방향만 제안하라는 제한을 함께 적는다.
     */
    static Observation generalReview() {
        return new Observation("O1", GENERAL_REVIEW, null,
                "확인된 부족한 점은 없음. 입력에 있는 운영시간과 교통 사실만 근거로 하고 새로운 문제나 수치를 만들지 말 것. "
                        + "이용 안내 공개, 운영 현황 점검, 이용 편의 보완 같은 일반적인 검토 방향만 제안할 것");
    }

    /** 같은 행정구·같은 유형 시설들의 선택 요일 마감 시각(중앙값)보다 이 시설이 일찍 닫음 */
    static final String PEER_LATER_CLOSING = "PEER_LATER_CLOSING";
    /** 같은 행정구·같은 유형 시설 대부분이 희망 시간에 운영하는데 이 시설은 운영하지 않음 */
    static final String PEER_OPEN_AT_REQUEST = "PEER_OPEN_AT_REQUEST";

    /** 비교 시설이 이 수 미만이면 비교하지 않는다(표본이 너무 작음) */
    static final int PEER_MIN_COUNT = 5;
    /** 이 시설의 마감이 비교 시설 중앙값보다 이 값(분) 이상 이르면 PEER_LATER_CLOSING */
    static final int PEER_CLOSING_GAP_MINUTES = 60;

    /**
     * 같은 행정구·같은 유형 시설과의 비교에서 나온 '부족한 점'. LLM에는 시설 수·비율 숫자를 주지 않고
     * 시각과 '대부분·절반 이상' 표현만 준다(숫자 검증은 입력에 있는 시각만 허용하기 때문).
     *
     * @param ownCloseMin 이 시설이 선택 요일에 가장 늦게 닫는 시각(분). 운영하지 않거나 모르면 null
     * @param ownOpen     이 시설이 희망 시간 전체를 운영하는가
     */
    static List<Observation> peerObservations(PeerHoursComparison.Result peers, Integer ownCloseMin,
                                              boolean ownOpen, int dayOfWeek, int startMin, int endMin) {
        List<Observation> out = new ArrayList<>();
        if (peers == null || peers.peerCount() < PEER_MIN_COUNT) {
            return out;
        }
        String day = DAY_LABELS[dayOfWeek];
        Integer median = peers.closeSampleCount() >= PEER_MIN_COUNT ? peers.medianCloseMin() : null;
        if (ownCloseMin != null && median != null && median - ownCloseMin >= PEER_CLOSING_GAP_MINUTES) {
            out.add(new Observation("", PEER_LATER_CLOSING, null, "같은 행정구의 같은 유형 시설들은 " + day
                    + " 마감 시각의 중앙값이 " + TimeText.format(median) + "인데 이 시설은 " + TimeText.format(ownCloseMin)
                    + "에 마감해 더 일찍 닫음"));
        } else if (ownCloseMin != null && median != null && ownCloseMin >= median) {
            // 부족하지 않아도 비교 결과를 알린다: 이웃 시설과 견줘 같거나 더 늦게까지 운영함
            out.add(new Observation("", PEER_COVERED, null, "같은 행정구의 같은 유형 시설들의 " + day
                    + " 마감 시각 중앙값은 " + TimeText.format(median) + "이고 이 시설은 " + TimeText.format(ownCloseMin)
                    + "까지 운영해 비슷하거나 더 늦게까지 운영함"));
        }
        double ratio = (double) peers.peersOpen() / peers.peerCount();
        if (!ownOpen && ratio >= 0.5) {
            out.add(new Observation("", PEER_OPEN_AT_REQUEST, null, "같은 행정구의 같은 유형 시설 "
                    + (ratio >= 0.7 ? "대부분" : "절반 이상") + "이 " + day + " 희망 시간("
                    + TimeText.format(startMin) + "~" + TimeText.format(endMin) + ")에 운영하는데 이 시설은 운영하지 않음"));
        }
        return out;
    }

    /** 같은 행정구·같은 유형 시설의 마감 중앙값과 견줘 이 시설이 같거나 더 늦게까지 운영함(부족하지 않은 비교 결과) */
    static final String PEER_COVERED = "PEER_COVERED";
    /** 유동 인구가 많은 시간대(권장 마감 시각)까지 이 시설이 운영함(부족하지 않은 비교 결과) */
    static final String FLOW_COVERED = "FLOW_COVERED";
    /** 동종 시설 비교와 유동인구 비교가 둘 다 충족일 때 하나로 묶은 관찰 */
    static final String COMPARED_COVERED = "COMPARED_COVERED";

    /** 경기도 유동인구 통계에서 유동 인구가 많은 시간대(권장 마감 시각)보다 이 시설이 일찍 닫음 */
    static final String FLOW_CLOSING = "FLOW_CLOSING";
    /** 희망 시간이 유동 인구가 많은 시간대인데 같은 구·같은 유형 시설 중 운영하는 곳이 절반에 못 미치고 이 시설도 운영하지 않음 */
    static final String FLOW_SUPPLY_GAP = "FLOW_SUPPLY_GAP";
    /** 주말 유동 인구가 평일 수준인데 이 시설은 그 요일에 휴관이거나 평일보다 일찍 닫음 */
    static final String FLOW_WEEKEND = "FLOW_WEEKEND";

    /** 이 시설의 마감이 권장 마감 시각보다 이 값(분) 이상 이르면 FLOW_CLOSING */
    static final int FLOW_CLOSING_GAP_MINUTES = 60;
    /** 주말 유동 인구가 평일 평균의 이 % 이상이면 평일 수준으로 본다 */
    static final int FLOW_WEEKEND_PERCENT = 85;

    /**
     * 경기도 유동인구 통계(구 단위)를 근거로 한 '부족한 점'. 연도·수치는 주지 않고 시각과 서술만 준다.
     * 문구는 "경기도 {구} 유동인구 통계"로 시작해 AI가 그대로 인용할 수 있게 한다.
     *
     * @param ownCloseMin 이 시설이 선택 요일에 가장 늦게 닫는 시각(분). 운영하지 않거나 모르면 null
     * @param ownOpen     이 시설이 희망 시간 전체를 운영하는가
     * @param peers       같은 구·같은 유형 시설 비교(없으면 null)
     * @param weekly      공간별 요일 7개 스케줄(없으면 null)
     */
    static List<Observation> flowObservations(FloatingPopulation flow, String district, Integer ownCloseMin,
                                              boolean ownOpen, PeerHoursComparison.Result peers,
                                              Map<SpaceType, List<DaySchedule>> weekly,
                                              int dayOfWeek, int startMin, int endMin) {
        List<Observation> out = new ArrayList<>();
        if (flow == null || district == null || !flow.hasZoneData(district)) {
            return out;
        }
        String source = "경기도 " + district + " 유동인구 통계";
        String day = DAY_LABELS[dayOfWeek];

        Integer suggested = flow.suggestedCloseMin(district);
        if (ownCloseMin != null && suggested != null && suggested - ownCloseMin >= FLOW_CLOSING_GAP_MINUTES) {
            out.add(new Observation("", FLOW_CLOSING, null, source + "를 비교하면 " + TimeText.format(suggested)
                    + "까지 유동 인구가 많은 시간대인데 이 시설은 " + day + " " + TimeText.format(ownCloseMin) + "에 마감함"));
        } else if (ownCloseMin != null && suggested != null && ownCloseMin >= suggested) {
            // 부족하지 않아도 비교 결과를 알린다: 유동 인구가 많은 시간대를 충족함
            out.add(new Observation("", FLOW_COVERED, null, source + "를 비교하면 " + TimeText.format(suggested)
                    + "까지 유동 인구가 많은 시간대인데 이 시설은 " + day + " " + TimeText.format(ownCloseMin)
                    + "까지 운영해 그 시간대를 충족함"));
        }

        if (!ownOpen && peers != null && peers.peerCount() >= PEER_MIN_COUNT
                && (double) peers.peersOpen() / peers.peerCount() < 0.5 && flow.busyAt(district, startMin, endMin)) {
            out.add(new Observation("", FLOW_SUPPLY_GAP, null, source + "에서 " + day + " 희망 시간("
                    + TimeText.format(startMin) + "~" + TimeText.format(endMin)
                    + ")은 유동 인구가 많은 시간대인데, 같은 행정구 같은 유형 시설 중 그 시간에 운영하는 시설은 절반에 못 미치고 이 시설도 운영하지 않음"));
        }

        Integer percent = flow.weekdayPercent(district, dayOfWeek);
        if (dayOfWeek >= 5 && percent != null && percent >= FLOW_WEEKEND_PERCENT && weekly != null) {
            Integer weekdayClose = weekdayLatestClose(weekly);
            if (weekdayClose != null && (ownCloseMin == null || weekdayClose - ownCloseMin >= FLOW_CLOSING_GAP_MINUTES)) {
                out.add(new Observation("", FLOW_WEEKEND, null, source + "에서 " + day
                        + "은 평일 못지않은 유동 인구가 있는데 이 시설은 " + day
                        + (ownCloseMin == null ? " 운영하지 않음" : " 운영시간이 평일보다 짧음")));
            }
        }
        return out;
    }

    /** 월~금 중 가장 늦은 마감(분). 운영하는 평일이 없으면 null */
    private static Integer weekdayLatestClose(Map<SpaceType, List<DaySchedule>> weekly) {
        Integer latest = null;
        for (List<DaySchedule> week : weekly.values()) {
            if (week == null || week.size() != 7) {
                continue;
            }
            for (int d = 0; d < 5; d++) {
                DaySchedule s = week.get(d);
                if (!"OPEN".equals(s.state())) {
                    continue;
                }
                for (TimeInterval i : s.intervals()) {
                    int close = toMin(i.closeTime());
                    latest = latest == null ? close : Math.max(latest, close);
                }
            }
        }
        return latest;
    }

    /** 동종 시설 마감 비교(PEER_LATER_CLOSING)와 유동인구 마감 비교(FLOW_CLOSING)가 함께 참일 때 하나로 묶은 관찰 */
    static final String COMPARED_CLOSING = "COMPARED_CLOSING";

    /**
     * 유동인구 통계 비교(FLOW_*)를 적용하는 시설 유형. 저녁·주말 연장 제안이 자연스러운 곳만 둔다.
     * 주민자치센터(행정복지센터)·보건소처럼 행정·진료 업무를 보는 곳에 "22시까지 운영"을 권하지 않기 위해서다.
     */
    static final Set<String> FLOW_TARGET_TYPES = Set.of("도서관", "생활체육시설", "문화센터");

    /**
     * 동종 시설 비교(PEER_*)와 유동인구 통계 비교(FLOW_*) 관찰을 모은다. 켜진 쪽만 계산한다.
     * 유동인구 비교는 {@link #FLOW_TARGET_TYPES} 유형에만 적용한다.
     * 마감 비교가 둘 다 참이면 같은 말을 두 번 하지 않도록 COMPARED_CLOSING 하나로 묶는다(비교 시설 중앙값 + 유동 인구 권장 마감).
     */
    static List<Observation> comparisonObservations(boolean peerEnabled, boolean flowEnabled,
                                                    PeerHoursComparison.Result peers, FloatingPopulation flow,
                                                    String typeName, String district, Integer ownCloseMin,
                                                    boolean ownOpen, Map<SpaceType, List<DaySchedule>> weekly,
                                                    int dayOfWeek, int startMin, int endMin) {
        List<Observation> peerObs = peerEnabled
                ? peerObservations(peers, ownCloseMin, ownOpen, dayOfWeek, startMin, endMin) : List.of();
        List<Observation> flowObs = flowEnabled && FLOW_TARGET_TYPES.contains(typeName)
                ? flowObservations(flow, district, ownCloseMin, ownOpen, peers, weekly, dayOfWeek, startMin, endMin)
                : List.of();
        boolean mergeClosing = hasType(peerObs, PEER_LATER_CLOSING) && hasType(flowObs, FLOW_CLOSING);
        boolean mergeCovered = hasType(peerObs, PEER_COVERED) && hasType(flowObs, FLOW_COVERED);
        List<Observation> out = new ArrayList<>();
        if (mergeClosing) {
            out.add(new Observation("", COMPARED_CLOSING, null, "같은 행정구의 같은 유형 시설들은 " + DAY_LABELS[dayOfWeek]
                    + " 마감 시각의 중앙값이 " + TimeText.format(peers.medianCloseMin()) + "이고 경기도 " + district
                    + " 유동인구 통계에서도 " + TimeText.format(flow.suggestedCloseMin(district))
                    + "까지 유동 인구가 많은 시간대인데 이 시설은 " + TimeText.format(ownCloseMin) + "에 마감함"));
        }
        if (mergeCovered) {
            out.add(new Observation("", COMPARED_COVERED, null, "같은 행정구의 같은 유형 시설들의 " + DAY_LABELS[dayOfWeek]
                    + " 마감 시각 중앙값은 " + TimeText.format(peers.medianCloseMin()) + "이고 경기도 " + district
                    + " 유동인구 통계에서도 " + TimeText.format(flow.suggestedCloseMin(district))
                    + "까지 유동 인구가 많은 시간대인데 이 시설은 " + TimeText.format(ownCloseMin)
                    + "까지 운영해 둘 다 충족함"));
        }
        for (Observation o : peerObs) {
            boolean merged = (mergeClosing && PEER_LATER_CLOSING.equals(o.type()))
                    || (mergeCovered && PEER_COVERED.equals(o.type()));
            if (!merged) {
                out.add(o);
            }
        }
        for (Observation o : flowObs) {
            boolean merged = (mergeClosing && FLOW_CLOSING.equals(o.type()))
                    || (mergeCovered && FLOW_COVERED.equals(o.type()));
            if (!merged) {
                out.add(o);
            }
        }
        return out;
    }

    private static boolean hasType(List<Observation> observations, String type) {
        return observations.stream().anyMatch(o -> type.equals(o.type()));
    }

    /** 요일별 운영 시간 길이가 이 값(분) 이상 차이 나면 DAY_VARIANCE (임의 기준, 해커톤용) */
    static final int VARIANCE_MINUTES = 120;
    /** 500m 내 정류장이 이 수 이하이면 FEW_BUS_STOPS (임의 기준, 시드 시설 약 12%) */
    static final int FEW_STOPS_MAX = 4;

    private static final String DAY_SHORT = "월화수목금토일";
    private static final String[] DAY_LABELS = { "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일" };

    /** @param spaceType 공간 단위 관찰이면 공간 코드, 시설 단위면 null */
    record Observation(String id, String type, @JsonInclude(JsonInclude.Include.NON_NULL) String spaceType,
                       String detail) {
    }

    private ImprovementObservations() {
    }

    /**
     * @param spaces  분석 대상 공간들(GENERAL → READING → DEFAULT 순서)
     * @param weekly  공간별 요일 7개 스케줄 (없는 공간은 요일 관찰을 건너뜀)
     * @param transport A05와 같은 교통 값 (AVAILABLE이고 정류장이 적을 때만 관찰)
     */
    static List<Observation> build(List<Candidate> spaces, Map<SpaceType, List<DaySchedule>> weekly,
                                   Accessibility transport) {
        List<Observation> out = new ArrayList<>();
        if (spaces.isEmpty()) {
            return out;
        }
        var facility = spaces.get(0).facility();
        boolean alwaysOpen = facility.getAccessType() == AccessType.ALWAYS_OPEN;
        for (Candidate c : spaces) {
            if (alwaysOpen) {
                break; // 하루 종일 개방이라 운영시간 관련 부족한 점이 없다
            }
            String space = c.spaceLabel();
            String day = DAY_LABELS[c.dayOfWeek()];
            switch (c.selectedSchedule().state()) {
                case "CLOSED" -> add(out, CLOSED_ON_REQUEST_DAY, c, space + " " + day + " 정기 휴관");
                case "UNKNOWN" -> add(out, HOURS_UNKNOWN, c, space + " " + day + " 운영시간 정보 없음");
                default -> {
                    List<Gap> gaps = c.availability().unavailableIntervals();
                    if (gaps != null && !gaps.isEmpty()) {
                        StringBuilder sb = new StringBuilder(space).append(' ').append(day).append(' ');
                        for (int i = 0; i < gaps.size(); i++) {
                            sb.append(i == 0 ? "" : ", ").append(gaps.get(i).startTime()).append('~')
                                    .append(gaps.get(i).endTime());
                        }
                        sb.append(" 미운영(현재 마감 ").append(TimeText.format(c.currentCloseMin())).append(')');
                        add(out, REQUEST_GAP, c, sb.toString());
                    }
                }
            }
            List<DaySchedule> week = weekly == null ? null : weekly.get(c.spaceType());
            if (week != null && week.size() == 7) {
                closedDays(out, c, week);
                variance(out, c, week);
            }
        }
        if (facility.getAccessType() == AccessType.RESERVATION) {
            out.add(new Observation(nextId(out), RESERVATION_REQUIRED, null, "예약제 시설"));
        }
        if (transport != null && transport.status() == Status.AVAILABLE && transport.stopCount() != null
                && transport.stopCount() <= FEW_STOPS_MAX) {
            // 숫자는 detail에 쓰지 않는다. 정류장 수는 입력의 busStopCount로만 전달해 숫자 검증을 엄격하게 유지한다
            out.add(new Observation(nextId(out), FEW_BUS_STOPS, null, "시설 인근 정류장 수가 적음"));
        }
        return out;
    }

    /** 선택 요일 외에 매주 정기 휴관인 요일 */
    private static void closedDays(List<Observation> out, Candidate c, List<DaySchedule> week) {
        List<String> closed = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            if (d != c.dayOfWeek() && "CLOSED".equals(week.get(d).state())) {
                closed.add(DAY_SHORT.substring(d, d + 1));
            }
        }
        if (!closed.isEmpty()) {
            add(out, REGULAR_CLOSED_DAYS, c, c.spaceLabel() + " 정기 휴관: " + String.join(", ", closed));
        }
    }

    /** 운영하는 요일 중 하루 운영 시간이 가장 짧은 날과 가장 긴 날의 차이가 크면 */
    private static void variance(List<Observation> out, Candidate c, List<DaySchedule> week) {
        int shortest = -1;
        int longest = -1;
        int shortMin = Integer.MAX_VALUE;
        int longMin = -1;
        for (int d = 0; d < 7; d++) {
            DaySchedule s = week.get(d);
            if (!"OPEN".equals(s.state())) {
                continue;
            }
            int total = minutes(s);
            if (total < shortMin) {
                shortMin = total;
                shortest = d;
            }
            if (total > longMin) {
                longMin = total;
                longest = d;
            }
        }
        if (shortest >= 0 && longMin - shortMin >= VARIANCE_MINUTES) {
            add(out, DAY_VARIANCE, c, c.spaceLabel() + " 가장 짧은 요일 " + DAY_SHORT.charAt(shortest) + " "
                    + intervals(week.get(shortest)) + ", 가장 긴 요일 " + DAY_SHORT.charAt(longest) + " "
                    + intervals(week.get(longest)));
        }
    }

    private static int minutes(DaySchedule s) {
        int sum = 0;
        for (TimeInterval i : s.intervals()) {
            sum += toMin(i.closeTime()) - toMin(i.openTime());
        }
        return sum;
    }

    private static int toMin(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    private static String intervals(DaySchedule s) {
        List<String> parts = new ArrayList<>();
        for (TimeInterval i : s.intervals()) {
            parts.add(i.openTime() + "~" + i.closeTime());
        }
        return String.join(", ", parts);
    }

    private static void add(List<Observation> out, String type, Candidate c, String detail) {
        out.add(new Observation(nextId(out), type, c.spaceType().name(), detail));
    }

    private static String nextId(List<Observation> out) {
        return "O" + (out.size() + 1);
    }
}
