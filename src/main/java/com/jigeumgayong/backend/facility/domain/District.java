package com.jigeumgayong.backend.facility.domain;

import java.util.List;

/** 행정구 (docs/10 §3). 값이 한글이라 enum 대신 문자열 상수로 둔다. DB CHECK와 같은 집합. */
public final class District {

    public static final String SUJI = "수지구";
    public static final String GIHEUNG = "기흥구";
    public static final String CHEOIN = "처인구";
    public static final List<String> ALL = List.of(SUJI, GIHEUNG, CHEOIN);

    private District() {
    }

    public static boolean isValid(String value) {
        return ALL.contains(value);
    }

    public static String require(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("지원하지 않는 district: " + value);
        }
        return value;
    }
}
