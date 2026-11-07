package com.jigeumgayong.backend.facility.etl;

import static com.jigeumgayong.backend.facility.etl.EtlUtil.blankToNull;
import static com.jigeumgayong.backend.facility.etl.EtlUtil.nospace;
import static com.jigeumgayong.backend.facility.etl.EtlUtil.s;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import com.jigeumgayong.backend.facility.etl.SourceFacility.SpaceData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 행정복지센터·수기 입력 시설과 PARKING·RENTAL_ROOM 태그 원천 변환 (docs/10 §3·§4). */
final class ExtraSources {

    /** 행정복지센터 원천에는 운영시간이 없어 민원실 기준 평일 09~18시, 주말 휴무로 둔다. */
    static final String ADMIN_HOURS = "0-4 09:00-18:00; 5-6 CLOSED";
    private static final String ADMIN_NOTE = "토·일·공휴일 휴무 (민원실 기준)";
    private static final String ADMIN_SUFFIX = "행정복지센터";
    /** 시청·구청은 시설로 적재하지 않는다 */
    private static final Set<String> EXCLUDE_ADMIN = Set.of("용인시청", "용인특례시청", "처인구청", "기흥구청", "수지구청");
    /** 2019 데이터 이후 바뀐 주소 (용인도시공사 주차장 CSV 2026-08-20 기준) */
    private static final Map<String, String> ADMIN_ADDR_OVERRIDE = Map.of("중앙동", "경기도 용인시 처인구 금령로90번길 3-20");
    /** 부설주차장 이름 → DB 시설명 (공백 제거 비교) */
    private static final Map<String, List<String>> PARKING_ALIAS = Map.of(
            "평생학습관", List.of("용인시평생학습관"),
            "동백미르휴먼센터", List.of("동백미르휴먼센터", "동백평생학습관", "동백동행정복지센터"),
            "보정미르휴먼센터", List.of("보정미르휴먼센터"),
            "기흥국민체육센터 통합", List.of("기흥국민체육센터"));

    /** 부설주차장 한 건. names 중 하나와 이름이 같은 시설에 PARKING 태그를 붙인다. */
    record ParkingTag(List<String> names, String detail) {
    }

    private ExtraSources() {
    }

    static List<SourceFacility> adminCenters(List<Map<String, String>> rows) {
        List<SourceFacility> out = new ArrayList<>();
        Map<Integer, List<Slot>> hours = HourParser.parseSpec(ADMIN_HOURS);
        for (Map<String, String> r : rows) {
            String n = s(r, "구분명");
            if (EXCLUDE_ADMIN.contains(n)) {
                continue;
            }
            boolean suffixed = n.endsWith(ADMIN_SUFFIX);
            String base = suffixed ? n.substring(0, n.length() - ADMIN_SUFFIX.length()).strip() : n;
            String addr = s(r, "주소");
            int paren = addr.indexOf('(');
            String cleaned = (paren < 0 ? addr : addr.substring(0, paren)).strip();
            SourceFacility f = SourceFacility.builder()
                    .sourceType(SourceType.ADMIN_CENTER).sourceKey("ADM-" + base)
                    .typeName(DefaultFacilityTypes.ADMIN_CENTER)
                    .name(suffixed ? n : base + ADMIN_SUFFIX)
                    .address(ADMIN_ADDR_OVERRIDE.getOrDefault(base, cleaned))
                    .accessType(AccessType.OPEN_HOURS)
                    .phone(EtlUtil.phone(s(r, "전화번호안내")))
                    .note(ADMIN_NOTE)
                    .updated(EtlUtil.dateOrNull(s(r, "데이터기준일자")))
                    .build();
            f.getSpaces().put(SpaceType.DEFAULT, new SpaceData("시설 전체", hours));
            out.add(f);
        }
        return out;
    }

    /** manual_facilities.csv: 보건소·평생학습관·문화센터 등 원천이 없는 시설. 형식 오류는 격리 사유로 남긴다. */
    static List<SourceFacility> manual(List<Map<String, String>> rows) {
        List<SourceFacility> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            SourceFacility f = SourceFacility.builder()
                    .sourceType(SourceType.MANUAL).sourceKey(s(r, "source_key"))
                    .typeName(s(r, "type_name")).name(s(r, "name")).address(s(r, "address"))
                    .phone(EtlUtil.phone(s(r, "phone")))
                    .note(blankToNull(s(r, "closed_day_note")))
                    .updated(EtlUtil.dateOrNull(s(r, "source_updated_at")))
                    .lat(EtlUtil.toDouble(s(r, "latitude"))).lng(EtlUtil.toDouble(s(r, "longitude")))
                    .build();
            try {
                f.setAccessType(AccessType.valueOf(s(r, "access_type")));
                f.getSpaces().put(SpaceType.DEFAULT, new SpaceData("시설 전체", HourParser.parseSpec(s(r, "hours"))));
            } catch (IllegalArgumentException e) {
                f.setInvalidReason(e.getMessage());
            }
            out.add(f);
        }
        return out;
    }

    /** 용인도시공사 주차장 중 다른 시설에 딸린 '부설'만. 독립 공영주차장은 운영시간 원천이 없어 적재하지 않는다. */
    static List<ParkingTag> parkingTags(List<Map<String, String>> rows) {
        List<ParkingTag> out = new ArrayList<>();
        for (Map<String, String> r : rows) {
            if (!"부설".equals(s(r, "구분"))) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String part : s(r, "주차장명").split(" 및 ")) {
                names.addAll(PARKING_ALIAS.getOrDefault(part, List.of(part)));
            }
            out.add(new ParkingTag(names, "주차 " + s(r, "주차대수") + "면"));
        }
        return out;
    }

    /** 사용 중(시설사용여부 Y)인 대관 공간을 도서관별로 묶어 '대관 2실 (소모임실, 세미나실)' 형태의 detail로 만든다. */
    static Map<String, String> rentalDetails(List<Map<String, String>> rows) {
        Map<String, Set<String>> rooms = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            if (!"Y".equals(s(r, "시설사용여부"))) {
                continue;
            }
            String lib = s(r, "도서관명");
            String key = lib.endsWith("도서관") ? lib : lib + "도서관";
            rooms.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(s(r, "시설명"));
        }
        Map<String, String> out = new LinkedHashMap<>();
        rooms.forEach((lib, names) -> out.put(lib,
                EtlUtil.truncate("대관 " + names.size() + "실 (" + String.join(", ", names) + ")", 100)));
        return out;
    }

    /** 이름의 공백을 뺀 형태로 비교하기 위한 헬퍼 */
    static boolean sameName(String a, String b) {
        return nospace(a).equals(nospace(b));
    }
}
