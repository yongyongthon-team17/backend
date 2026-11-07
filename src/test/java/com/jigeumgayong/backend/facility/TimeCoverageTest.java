package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.facility.service.TimeCoverage;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeCoverageTest {

    private static Span s(String open, String close) {
        return new Span(min(open), min(close));
    }

    private static int min(String t) {
        return "24:00".equals(t) ? 1440 : TimeText.parseStart(t);
    }

    @Test
    void start_equals_open_and_end_equals_close_is_covered() {
        assertThat(TimeCoverage.covers(List.of(s("09:00", "18:00")), min("09:00"), min("18:00"))).isTrue();
    }

    @Test
    void start_before_open_is_not_covered_and_gap_reported() {
        var gaps = TimeCoverage.findGaps(List.of(s("09:00", "18:00")), min("08:00"), min("10:00"));
        assertThat(gaps).containsExactly(s("08:00", "09:00"));
    }

    @Test
    void end_after_close_leaves_tail_gap() {
        var gaps = TimeCoverage.findGaps(List.of(s("09:00", "20:00")), min("19:00"), min("21:00"));
        assertThat(gaps).containsExactly(s("20:00", "21:00"));
    }

    @Test
    void midnight_close_1440() {
        assertThat(TimeCoverage.covers(List.of(s("09:00", "24:00")), min("22:00"), 1440)).isTrue();
    }

    @Test
    void adjacent_intervals_are_continuous() {
        var list = List.of(s("12:00", "18:00"), s("09:00", "12:00"));
        assertThat(TimeCoverage.covers(list, min("10:00"), min("17:00"))).isTrue();
    }

    @Test
    void lunch_break_leaves_gap() {
        var list = List.of(s("09:00", "12:00"), s("13:00", "18:00"));
        assertThat(TimeCoverage.findGaps(list, min("10:00"), min("15:00"))).containsExactly(s("12:00", "13:00"));
    }

    @Test
    void overlapping_intervals_are_handled() {
        var list = List.of(s("09:00", "13:00"), s("12:00", "18:00"));
        assertThat(TimeCoverage.covers(list, min("10:00"), min("17:00"))).isTrue();
    }

    @Test
    void request_fully_outside_intervals() {
        var gaps = TimeCoverage.findGaps(List.of(s("09:00", "12:00")), min("14:00"), min("16:00"));
        assertThat(gaps).containsExactly(s("14:00", "16:00"));
    }

    @Test
    void no_intervals_means_whole_range_is_gap() {
        assertThat(TimeCoverage.findGaps(List.of(), 600, 700)).containsExactly(new Span(600, 700));
    }

    @Test
    void time_text_parsing_and_formatting() {
        assertThat(TimeText.parseStart("00:00")).isZero();
        assertThat(TimeText.parseStart("23:59")).isEqualTo(1439);
        assertThat(TimeText.parseEnd("00:01")).isEqualTo(1);
        assertThat(TimeText.parseEnd("24:00")).isEqualTo(1440);
        assertThat(TimeText.format(1440)).isEqualTo("24:00");
        assertThat(TimeText.format(540)).isEqualTo("09:00");
    }

    @Test
    void invalid_times_throw_invalid_time_range() {
        for (Runnable r : List.<Runnable>of(
                () -> TimeText.parseStart("24:00"),
                () -> TimeText.parseStart("9:00"),
                () -> TimeText.parseStart("ab"),
                () -> TimeText.parseStart("10:60"),
                () -> TimeText.parseEnd("00:00"),
                () -> TimeText.parseEnd("24:01"),
                () -> TimeText.parseRange("10:00", "10:00"),
                () -> TimeText.parseRange("11:00", "10:00"))) {
            assertThatThrownBy(r::run).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TIME_RANGE));
        }
    }
}
