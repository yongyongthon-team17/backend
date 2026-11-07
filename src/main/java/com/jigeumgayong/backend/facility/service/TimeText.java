package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import java.util.List;
import java.util.regex.Pattern;

/** "HH:mm" ↔ 분 변환과 희망 시간 검증 (07 §3.3-2, §3.5). 24:00 = 1440. */
public final class TimeText {

    private static final Pattern HHMM = Pattern.compile("^(\\d{2}):(\\d{2})$");

    private TimeText() {
    }

    /** 엄격한 HH:mm → 분. 24:00은 허용(1440). 범위 검사는 호출부(parseStart/parseEnd)에서 한다. */
    private static int toMinutes(String text, String field) {
        if (text == null) {
            throw invalid(field, ErrorDetail.INVALID_FORMAT);
        }
        var m = HHMM.matcher(text);
        if (!m.matches()) {
            throw invalid(field, ErrorDetail.INVALID_FORMAT);
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (min > 59 || h > 24 || (h == 24 && min != 0)) {
            throw invalid(field, ErrorDetail.OUT_OF_RANGE);
        }
        return h * 60 + min;
    }

    /** 시작: 00:00~23:59 */
    public static int parseStart(String text) {
        int v = toMinutes(text, "startTime");
        if (v > 1439) {
            throw invalid("startTime", ErrorDetail.OUT_OF_RANGE);
        }
        return v;
    }

    /** 종료: 00:01~24:00 */
    public static int parseEnd(String text) {
        int v = toMinutes(text, "endTime");
        if (v < 1) {
            throw invalid("endTime", ErrorDetail.OUT_OF_RANGE);
        }
        return v;
    }

    /** 시작·종료를 함께 검증하고 start < end를 확인한다. */
    public static int[] parseRange(String startText, String endText) {
        int start = parseStart(startText);
        int end = parseEnd(endText);
        if (end <= start) {
            throw invalid("endTime", "MUST_BE_AFTER_START_TIME");
        }
        return new int[] { start, end };
    }

    public static String format(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_TIME_RANGE, ErrorCode.INVALID_TIME_RANGE.defaultMessage(),
                List.of(new ErrorDetail(field, reason)));
    }
}
