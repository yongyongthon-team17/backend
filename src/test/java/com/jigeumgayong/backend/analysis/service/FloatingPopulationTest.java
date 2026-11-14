package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 유동인구 요약(구별 시간대·요일) 계산. 값 배열을 직접 넣은 작은 예와 실제 seed CSV 둘 다 확인한다. */
class FloatingPopulationTest {

    /** 시간 길이가 같은 가정의 단순한 예: TZ09(21~23시)만 평균보다 높다 */
    private static FloatingPopulation sample() {
        double[] zone = { 10, 10, 10, 10, 10, 10, 10, 10, 40, 10 }; // TZ01~TZ10
        double[] weekday = { 100, 100, 100, 100, 100, 85, 60 }; // 월~일
        return new FloatingPopulation(Map.of("처인구", zone), Map.of("처인구", weekday));
    }

    @Test
    void 하루_평균은_시간대_값을_시간_길이로_가중해_구한다() {
        // (7시간×10 + 14시간×10 + 2시간×40 + 1시간×10) / 24 = 12.5
        assertThat(sample().dayMean("처인구")).isEqualTo(12.5);
    }

    @Test
    void 권장_마감은_평균_이상인_마지막_시간대의_끝이고_22시를_넘지_않는다() {
        // 평균 이상인 마지막 시간대는 TZ09(~23:00)이지만 상한 22:00으로 제한
        assertThat(sample().suggestedCloseMin("처인구")).isEqualTo(22 * 60);
    }

    @Test
    void 평균_이상인_시간대가_22시_이전에_끝나면_그_끝이_권장_마감이다() {
        double[] zone = { 5, 5, 5, 20, 20, 20, 20, 5, 5, 5 }; // TZ04~TZ07(11~19시)만 높음
        FloatingPopulation flow = new FloatingPopulation(Map.of("수지구", zone), Map.of());

        assertThat(flow.suggestedCloseMin("수지구")).isEqualTo(19 * 60);
    }

    @Test
    void 희망_시간이_걸치는_시간대의_가중_평균으로_붐비는지_판단한다() {
        FloatingPopulation flow = sample();

        assertThat(flow.busyAt("처인구", 21 * 60, 23 * 60)).isTrue(); // TZ09 전부 겹침
        assertThat(flow.busyAt("처인구", 19 * 60, 21 * 60)).isFalse(); // TZ08은 평균 미만
        // 20:00~22:00은 TZ08과 TZ09가 1시간씩: (10+40)/2 = 25 ≥ 12.5
        assertThat(flow.busyAt("처인구", 20 * 60, 22 * 60)).isTrue();
        assertThat(flow.busyAt("처인구", 18 * 60 + 30, 19 * 60)).isFalse();
    }

    @Test
    void 요일_비율은_월에서_금_평균_대비_정수_퍼센트다() {
        FloatingPopulation flow = sample();

        assertThat(flow.weekdayPercent("처인구", 4)).isEqualTo(100);
        assertThat(flow.weekdayPercent("처인구", 5)).isEqualTo(85);
        assertThat(flow.weekdayPercent("처인구", 6)).isEqualTo(60);
    }

    @Test
    void 자료가_없는_구는_비교하지_않는다() {
        FloatingPopulation flow = sample();

        assertThat(flow.hasZoneData("성남구")).isFalse();
        assertThat(flow.suggestedCloseMin("성남구")).isNull();
        assertThat(flow.busyAt("성남구", 19 * 60, 21 * 60)).isFalse();
        assertThat(flow.weekdayPercent("성남구", 5)).isNull();
    }

    @Test
    void 실제_seed_CSV는_세_구를_읽고_세_구_모두_권장_마감이_22시다() {
        FloatingPopulation flow = new FloatingPopulation();

        for (String district : new String[] { "처인구", "기흥구", "수지구" }) {
            assertThat(flow.hasZoneData(district)).as(district).isTrue();
            assertThat(flow.suggestedCloseMin(district)).as(district).isEqualTo(22 * 60);
            // 저녁 19~21시는 세 구 모두 하루 평균보다 붐비는 시간대(2024-11 자료 기준)
            assertThat(flow.busyAt(district, 19 * 60, 21 * 60)).as(district).isTrue();
            assertThat(flow.weekdayPercent(district, 5)).as(district).isBetween(70, 100); // 토요일은 평일보다 약간 낮다
        }
    }
}
