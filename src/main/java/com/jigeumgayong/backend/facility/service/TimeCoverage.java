package com.jigeumgayong.backend.facility.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 운영 구간들이 희망 구간 [start, end)을 빈틈없이 덮는지 판정하는 순수 함수 모음.
 * 시각은 자정부터의 분(0~1440). 맞닿은 구간(12:00 닫음 + 12:00 열음)은 이어진 것으로 본다.
 */
public final class TimeCoverage {

    /** [open, close) 분 구간 */
    public record Span(int open, int close) {
    }

    private TimeCoverage() {
    }

    /** 희망 구간 중 운영 구간으로 덮이지 않는 부분. 비어 있으면 전부 덮은 것. */
    public static List<Span> findGaps(List<Span> intervals, int start, int end) {
        List<Span> sorted = intervals.stream().sorted(Comparator.comparingInt(Span::open)).toList();
        List<Span> gaps = new ArrayList<>();
        int cursor = start;
        for (Span s : sorted) {
            if (cursor >= end) {
                break;
            }
            if (s.open() > cursor) {
                gaps.add(new Span(cursor, Math.min(s.open(), end)));
            }
            cursor = Math.max(cursor, s.close());
        }
        if (cursor < end) {
            gaps.add(new Span(cursor, end));
        }
        return gaps;
    }

    public static boolean covers(List<Span> intervals, int start, int end) {
        return findGaps(intervals, start, end).isEmpty();
    }
}
