package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.District;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/** ETL 공통 문자열·숫자 유틸. 원천 CSV는 값이 비거나 공백이 섞여 있어 모두 null 안전하게 다룬다. */
final class EtlUtil {

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private EtlUtil() {
    }

    /** CSV 한 행에서 컬럼 값을 trim해서 꺼낸다. 컬럼이 없거나 값이 없으면 빈 문자열. */
    static String s(Map<String, String> row, String column) {
        return strip(row.get(column));
    }

    static String strip(String v) {
        return v == null ? "" : v.strip();
    }

    static String nospace(String v) {
        return v == null ? "" : WHITESPACE.matcher(v).replaceAll("");
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    static String truncate(String v, int max) {
        return v != null && v.length() > max ? v.substring(0, max) : v;
    }

    /** 전화번호: trim 후 DB 길이(20)에 맞춘다 (R18). */
    static String phone(String v) {
        return blankToNull(truncate(strip(v), 20));
    }

    /** yyyy-MM-dd만 인정한다. 그 외는 null. */
    static LocalDate dateOrNull(String v) {
        String t = strip(v);
        return ISO_DATE.matcher(t).matches() ? LocalDate.parse(t) : null;
    }

    static Double toDouble(String v) {
        try {
            return Double.parseDouble(strip(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 행정구는 원천 '행정구명' 컬럼이 아니라 주소 문자열에서 판별한다 (R18). 앞쪽 후보가 우선. */
    static String districtOf(String... candidates) {
        for (String c : candidates) {
            for (String d : District.ALL) {
                if (c != null && c.contains(d)) {
                    return d;
                }
            }
        }
        return null;
    }

    /** 지오코딩 캐시 조회용 주소 정리: '일원', '번지', 뒤에 붙은 여러 번지를 제거한다. */
    static String geoQuery(String address) {
        String a = address.replaceAll("(번지)?\\s*일원$", "");
        a = a.replace("번지", "");
        a = a.replaceAll("(\\d+(?:-\\d+)?)(\\s+\\d+(?:-\\d+)?)+$", "$1");
        return a.strip();
    }

    /** 원천에 고유번호가 없는 공공시설개방정보의 source_key 생성용 해시 (앞 12자). */
    static String md5Hex12(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
