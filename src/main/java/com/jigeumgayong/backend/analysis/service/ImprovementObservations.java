package com.jigeumgayong.backend.analysis.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
