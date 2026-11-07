package com.jigeumgayong.backend.facility.domain;

import java.util.List;

/** 화면 필터 칩 4개 (docs/10 §2). '전체' 칩은 조건 없음이라 저장하지 않는다. */
public final class ChipGroup {

    public static final String LIBRARY = "도서관";
    public static final String SPORTS = "체육시설";
    public static final String CULTURE = "문화공간";
    public static final String WELFARE = "복지시설";
    public static final List<String> ALL = List.of(LIBRARY, SPORTS, CULTURE, WELFARE);

    private ChipGroup() {
    }

    public static boolean isValid(String value) {
        return ALL.contains(value);
    }

    public static String require(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("지원하지 않는 chip_group: " + value);
        }
        return value;
    }
}
