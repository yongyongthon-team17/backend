package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A02·A03이 함께 쓰는 operating_hour 행 → 구간 변환. */
final class OperatingHours {

    private OperatingHours() {
    }

    /**
     * OPEN 구간(유효한 시각 쌍)만 모아 돌려준다. OPEN이 하나도 없으면 null.
     * (DDL CHECK상 OPEN과 CLOSED/UNKNOWN은 같은 요일에 섞이지 않지만 방어적으로 OPEN을 우선한다.)
     */
    static List<Span> openSpans(List<OperatingHour> rows) {
        List<Span> spans = new ArrayList<>();
        for (OperatingHour h : rows) {
            if (h.getDayState() == DayState.OPEN && h.getOpenMin() != null && h.getCloseMin() != null) {
                spans.add(new Span(h.getOpenMin(), h.getCloseMin()));
            }
        }
        return spans.isEmpty() ? null : spans;
    }

    /** 개장 시각 순으로 정렬해 "HH:mm" 구간으로 변환 */
    static List<TimeInterval> toIntervals(List<Span> spans) {
        return spans.stream()
                .sorted(Comparator.comparingInt(Span::open))
                .map(s -> new TimeInterval(TimeText.format(s.open()), TimeText.format(s.close())))
                .toList();
    }

    /** 한 요일의 행들 → DaySchedule. OPEN 구간 > CLOSED > UNKNOWN 순으로 우선한다. */
    static DaySchedule daySchedule(int day, List<OperatingHour> rows) {
        List<Span> open = openSpans(rows);
        if (open != null) {
            return new DaySchedule(day, "OPEN", toIntervals(open));
        }
        boolean closed = rows.stream().anyMatch(h -> h.getDayState() == DayState.CLOSED);
        return new DaySchedule(day, closed ? "CLOSED" : "UNKNOWN", List.of());
    }
}
