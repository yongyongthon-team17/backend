package com.jigeumgayong.backend.analysis.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 경기도 유동인구 통계(구별 시간대·요일 요약)를 메모리에 두고 A04 개선안의 근거 사실을 계산한다.
 * 값은 seed/floating_zone.csv(시간대 10구간), seed/floating_weekday.csv(요일 7개)이며
 * scripts/prepare_floating_population.py로 만든다. 구 단위 일평균이라 시설별 차이는 반영하지 않는다.
 * DB는 쓰지 않는다.
 */
@Component
final class FloatingPopulation {

    /** 시간대코드 TZ01~TZ10의 [시작시, 끝시) — 분석시스템 코드정보 기준 */
    static final int[][] ZONE_HOURS = { { 0, 7 }, { 7, 9 }, { 9, 11 }, { 11, 13 }, { 13, 15 }, { 15, 17 },
            { 17, 19 }, { 19, 21 }, { 21, 23 }, { 23, 24 } };
    /** 권장 마감 시각 상한(분). 유동 인구가 밤까지 많아도 공공시설에 무리한 제안을 하지 않는다 */
    static final int CLOSING_CAP_MIN = 22 * 60;

    private final Map<String, double[]> zoneValues;
    private final Map<String, double[]> weekdayValues;

    @Autowired
    FloatingPopulation() {
        this(load("seed/floating_zone.csv", 10, 1), load("seed/floating_weekday.csv", 7, 0));
    }

    /** 테스트용: 구 → 값 배열을 직접 넣는다 */
    FloatingPopulation(Map<String, double[]> zoneValues, Map<String, double[]> weekdayValues) {
        this.zoneValues = zoneValues;
        this.weekdayValues = weekdayValues;
    }

    private static Map<String, double[]> load(String path, int size, int indexBase) {
        Map<String, double[]> out = new HashMap<>();
        try (InputStream in = new ClassPathResource(path).getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.readLine(); // 머리글
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length < 3) {
                    continue;
                }
                double[] values = out.computeIfAbsent(p[0], k -> new double[size]);
                values[Integer.parseInt(p[1]) - indexBase] = Double.parseDouble(p[2]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("유동인구 요약 파일을 읽지 못했습니다: " + path, e);
        }
        return out;
    }

    boolean hasZoneData(String district) {
        return zoneValues.containsKey(district);
    }

    /** 하루 평균 수준: 시간대 값을 시간 길이로 가중한 평균 */
    double dayMean(String district) {
        double[] v = zoneValues.get(district);
        double sum = 0;
        for (int z = 0; z < ZONE_HOURS.length; z++) {
            sum += v[z] * (ZONE_HOURS[z][1] - ZONE_HOURS[z][0]);
        }
        return sum / 24;
    }

    /**
     * 유동 인구가 하루 평균 이상인 마지막 시간대의 끝 시각(분)을 {@link #CLOSING_CAP_MIN}으로 제한한 값.
     * 그 구의 자료가 없거나 평균 이상인 시간대가 없으면 null.
     */
    Integer suggestedCloseMin(String district) {
        if (!hasZoneData(district)) {
            return null;
        }
        double[] v = zoneValues.get(district);
        double mean = dayMean(district);
        Integer last = null;
        for (int z = 0; z < ZONE_HOURS.length; z++) {
            if (v[z] >= mean) {
                last = ZONE_HOURS[z][1] * 60;
            }
        }
        return last == null ? null : Math.min(last, CLOSING_CAP_MIN);
    }

    /**
     * 희망 시간 [startMin, endMin)이 유동 인구가 하루 평균 이상인 시간대인가.
     * 시간대 값을 겹치는 시간(분)으로 가중한 평균을 하루 평균과 비교한다.
     */
    boolean busyAt(String district, int startMin, int endMin) {
        if (!hasZoneData(district) || endMin <= startMin) {
            return false;
        }
        double[] v = zoneValues.get(district);
        double weighted = 0;
        int covered = 0;
        for (int z = 0; z < ZONE_HOURS.length; z++) {
            int overlap = Math.min(endMin, ZONE_HOURS[z][1] * 60) - Math.max(startMin, ZONE_HOURS[z][0] * 60);
            if (overlap > 0) {
                weighted += v[z] * overlap;
                covered += overlap;
            }
        }
        return covered > 0 && weighted / covered >= dayMean(district);
    }

    /** 선택 요일(0=월 … 6=일)의 유동 인구가 월~금 평균의 몇 %인가(반올림). 자료가 없으면 null */
    Integer weekdayPercent(String district, int dayOfWeek) {
        double[] v = weekdayValues.get(district);
        if (v == null) {
            return null;
        }
        double weekdayMean = (v[0] + v[1] + v[2] + v[3] + v[4]) / 5;
        return weekdayMean <= 0 ? null : (int) Math.round(v[dayOfWeek] / weekdayMean * 100);
    }
}
