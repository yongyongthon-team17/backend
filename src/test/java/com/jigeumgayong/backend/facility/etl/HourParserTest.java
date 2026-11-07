package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.etl.HourParser.ClosedDays;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import com.jigeumgayong.backend.facility.etl.HourParser.SmallHours;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HourParserTest {

    @Test
    void 시각을_분으로_바꾸고_24시는_1440이다() {
        assertThat(HourParser.toMin("09:00")).isEqualTo(540);
        assertThat(HourParser.toMin("24:00")).isEqualTo(1440);
        assertThat(HourParser.toMin("09:30:00")).isEqualTo(570);
        assertThat(HourParser.toMin("25:00")).isNull();
        assertThat(HourParser.toMin("")).isNull();
        assertThat(HourParser.toMin("9시")).isNull();
    }

    @Test
    void 구간은_OPEN_CLOSED_결측으로_나뉜다() {
        assertThat(HourParser.interval("09:00", "18:00")).isEqualTo(Slot.open(540, 1080));
        assertThat(HourParser.interval("00:00", "00:00")).isEqualTo(Slot.CLOSED);
        assertThat(HourParser.interval("09:00", "00:00")).isEqualTo(Slot.open(540, 1440)); // 종료 00:00 = 자정 마감
        assertThat(HourParser.interval("09:00", "24:00")).isEqualTo(Slot.open(540, 1440));
        assertThat(HourParser.interval("22:00", "02:00")).isNull(); // 자정 넘김은 표현 불가
        assertThat(HourParser.interval("", "")).isNull();
    }

    @Test
    void 겹치거나_맞닿은_구간은_합친다() {
        List<Slot> merged = HourParser.mergeIntervals(List.of(
                Slot.open(780, 1080), Slot.open(540, 720), Slot.open(720, 800)));
        assertThat(merged).containsExactly(Slot.open(540, 1080));
        assertThat(HourParser.mergeIntervals(List.of(Slot.open(540, 600), Slot.open(700, 800))))
                .containsExactly(Slot.open(540, 600), Slot.open(700, 800));
    }

    @Test
    void 매주_고정_휴관만_요일로_잡고_격주는_note_전용이다() {
        assertThat(HourParser.parseClosedDays("월").days()).containsExactly(0);
        assertThat(HourParser.parseClosedDays("월+화").days()).containsExactlyInAnyOrder(0, 1);
        assertThat(HourParser.parseClosedDays("일요일").days()).containsExactly(6);

        ClosedDays biweekly = HourParser.parseClosedDays("둘째 주 월+넷째 주 월");
        assertThat(biweekly.days()).isEmpty();
        assertThat(biweekly.vague()).isTrue();

        assertThat(HourParser.parseClosedDays("연중무휴").days()).isEmpty();
        assertThat(HourParser.parseClosedDays("공휴일").days()).isEmpty();
        assertThat(HourParser.parseClosedDays("공휴일").vague()).isFalse();
    }

    @Test
    void 평일_토_일을_7일로_풀고_휴관_요일은_CLOSED_구간이_없으면_UNKNOWN이다() {
        Map<Integer, List<Slot>> w = HourParser.weekFrom(
                List.of(Slot.open(540, 1080)), List.of(Slot.open(540, 780)), null, Set.of(0));
        assertThat(w.get(0)).containsExactly(Slot.CLOSED);
        assertThat(w.get(1)).containsExactly(Slot.open(540, 1080));
        assertThat(w.get(5)).containsExactly(Slot.open(540, 780));
        assertThat(w.get(6)).containsExactly(Slot.UNKNOWN);
    }

    @Test
    void 작은도서관_이용시간_자유_텍스트를_읽는다() {
        SmallHours h = HourParser.parseSmall("평일 09:00~18:00 / 토요일 09:00~13:00 / 일요일 휴관", "N");
        assertThat(h.hours().get(2)).containsExactly(Slot.open(540, 1080));
        assertThat(h.hours().get(5)).containsExactly(Slot.open(540, 780));
        assertThat(h.hours().get(6)).containsExactly(Slot.CLOSED);
        assertThat(h.flag()).isEmpty();
    }

    @Test
    void 한글_시각_표기와_요일_범위를_읽는다() {
        SmallHours h = HourParser.parseSmall("월~금 9시~18시", "N");
        assertThat(h.hours().get(0)).containsExactly(Slot.open(540, 1080));
        assertThat(h.hours().get(4)).containsExactly(Slot.open(540, 1080));
        assertThat(h.hours().get(5)).containsExactly(Slot.UNKNOWN); // 토·일 미기재는 휴관으로 추정하지 않는다
        assertThat(h.flag()).isEqualTo("일부 요일 미기재/격주");
    }

    @Test
    void 작은도서관_휴관_결측_격주는_UNKNOWN_또는_CLOSED로_안전하게_처리한다() {
        assertThat(HourParser.parseSmall("09:00~18:00", "Y").hours().get(0)).containsExactly(Slot.CLOSED);
        SmallHours empty = HourParser.parseSmall("", "N");
        assertThat(empty.hours().get(0)).containsExactly(Slot.UNKNOWN);
        assertThat(empty.flag()).isEqualTo("이용시간 없음");
        SmallHours biweekly = HourParser.parseSmall("첫째 주 월요일 휴관", "N");
        assertThat(biweekly.hours().values()).allSatisfy(s -> assertThat(s.get(0).state()).isEqualTo(DayState.UNKNOWN));
        assertThat(biweekly.flag()).isEqualTo("파싱 실패");
        assertThat(HourParser.parseSmall("평일 09:00~18:00 (어린이자료실)", "N").flag())
                .isEqualTo("공간별 시간이 달라 자동 해석 안 함");
    }

    @Test
    void 수기_hours_문자열을_읽는다() {
        Map<Integer, List<Slot>> w = HourParser.parseSpec("0-4 09:00-18:00; 5-6 CLOSED");
        assertThat(w.get(0)).containsExactly(Slot.open(540, 1080));
        assertThat(w.get(6)).containsExactly(Slot.CLOSED);

        Map<Integer, List<Slot>> split = HourParser.parseSpec("1-3 09:00-12:00,13:00-18:00");
        assertThat(split.get(1)).containsExactly(Slot.open(540, 720), Slot.open(780, 1080)); // 점심 휴게 = interval_seq 2개
        assertThat(split.get(0)).containsExactly(Slot.UNKNOWN);

        assertThat(HourParser.parseSpec("0 09:00-24:00").get(0)).containsExactly(Slot.open(540, 1440));
        assertThat(HourParser.parseSpec("")).hasSize(7);
        assertThat(HourParser.parseSpec("ALWAYS")).isNull();
    }

    @Test
    void 수기_hours_형식_오류는_예외로_알린다() {
        assertThatThrownBy(() -> HourParser.parseSpec("0-4 18:00-09:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HourParser.parseSpec("0-7 09:00-18:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HourParser.parseSpec("월 09:00-18:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HourParser.parseSpec("0-4")).isInstanceOf(IllegalArgumentException.class);
    }
}
