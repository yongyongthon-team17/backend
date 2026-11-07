package com.jigeumgayong.backend.facility.etl;

import static com.jigeumgayong.backend.facility.etl.EtlUtil.blankToNull;
import static com.jigeumgayong.backend.facility.etl.EtlUtil.nospace;
import static com.jigeumgayong.backend.facility.etl.EtlUtil.s;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.HourParser.ClosedDays;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import com.jigeumgayong.backend.facility.etl.HourParser.SmallHours;
import com.jigeumgayong.backend.facility.etl.SourceFacility.SpaceData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 도서관(공공·작은)과 공공시설개방정보(생활체육) 원천 변환. 규칙은 docs/10 §3·§5·§6, §9 ETL 규칙을 따른다. */
final class CoreSources {

    /** 도서관 표준데이터에는 일요일 컬럼이 없다. 휴관일에 '일'이 없으면 일요일 = 토요일 시간으로 본다 (팀 확인 필요). */
    static final boolean SUNDAY_FROM_SATURDAY = true;
    private static final String LIB_STD_ONLY_TYPE = "공공도서관";
    private static final String SUNDAY_NOTE = "일요일 시간은 토요일 기준(원천에 일요일 없음)";
    private static final Pattern ROAD_KEY = Pattern.compile("([가-힣]+로(?:\\d+번길)?|[가-힣]+길)\\s*(\\d+(?:-\\d+)?)");

    private CoreSources() {
    }

    static List<SourceFacility> build(List<Map<String, String>> libStd, List<Map<String, String>> small,
                                      List<Map<String, String>> reading, List<Map<String, String>> opens,
                                      Map<String, Integer> notes) {
        List<SourceFacility> out = new ArrayList<>();
        Map<String, SourceFacility> stdByName = libStd(libStd, small, notes, out);
        reading(reading, stdByName, notes);
        libSmall(small, notes, out);
        publicOpen(opens, out);
        return out;
    }

    // ------------------------------------------------------------ LIB_STD

    private static Map<String, SourceFacility> libStd(List<Map<String, String>> rows, List<Map<String, String>> small,
                                                      Map<String, Integer> notes, List<SourceFacility> out) {
        Set<String> smallNames = new HashSet<>();
        small.forEach(r -> smallNames.add(nospace(s(r, "도서관명"))));

        Set<List<String>> seen = new HashSet<>();
        Map<String, SourceFacility> byName = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            String name = s(r, "도서관명");
            String addr = s(r, "소재지도로명주소");
            if (!seen.add(List.of(nospace(name), nospace(addr)))) {
                note(notes, "LIB_STD 중복 행 제거(이름+주소 동일)");
                continue;
            }
            if (!LIB_STD_ONLY_TYPE.equals(s(r, "도서관유형"))) {
                if (smallNames.contains(nospace(name))) {
                    note(notes, "LIB_STD 작은도서관 → LIB_SMALL 과 중복이라 제외");
                    continue;
                }
                note(notes, "LIB_STD 작은도서관인데 작은도서관 CSV에 없어 LIB_STD 로 적재");
            }
            Set<Integer> closed = HourParser.parseClosedDays(s(r, "휴관일")).days();
            Slot weekday = HourParser.interval(s(r, "평일운영시작시각"), s(r, "평일운영종료시각"));
            Slot sat = HourParser.interval(s(r, "토요일운영시작시각"), s(r, "토요일운영종료시각"));
            Slot sun = SUNDAY_FROM_SATURDAY && !closed.contains(6) ? sat : null;
            if (sat != null && sat.state() == DayState.CLOSED) {
                sun = sat;
            }
            Map<Integer, List<Slot>> hours = HourParser.weekFrom(one(weekday), one(sat), one(sun), closed);

            String note = s(r, "휴관일");
            if (SUNDAY_FROM_SATURDAY && !closed.contains(6) && sat != null && sat.state() == DayState.OPEN) {
                note = (note.isEmpty() ? "" : note + " / ") + SUNDAY_NOTE;
            }
            SourceFacility f = SourceFacility.builder()
                    .sourceType(SourceType.LIB_STD).sourceKey("LIB-" + nospace(name))
                    .typeName(DefaultFacilityTypes.LIBRARY).name(name).address(addr)
                    .district(EtlUtil.districtOf(addr)).accessType(AccessType.OPEN_HOURS)
                    .phone(EtlUtil.phone(s(r, "도서관전화번호")))
                    .note(blankToNull(EtlUtil.truncate(note, 255)))
                    .updated(EtlUtil.dateOrNull(s(r, "데이터기준일자")))
                    .lat(EtlUtil.toDouble(s(r, "위도"))).lng(EtlUtil.toDouble(s(r, "경도")))
                    .build();
            f.getSpaces().put(SpaceType.GENERAL, new SpaceData("종합자료실", hours));
            byName.put(nospace(name), f);
            out.add(f);
        }
        return byName;
    }

    // ------------------------------------------------------------ READING (열람실 그룹)

    /** 도서관당 열람실 그룹이 여럿이어도 운영시간 합집합 하나의 READING 공간으로 만든다. */
    private static void reading(List<Map<String, String>> rows, Map<String, SourceFacility> stdByName,
                                Map<String, Integer> notes) {
        Map<String, List<Map<String, String>>> byLibrary = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            byLibrary.computeIfAbsent(nospace(s(r, "도서관명")), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<Map<String, String>>> e : byLibrary.entrySet()) {
            SourceFacility f = stdByName.get(e.getKey());
            if (f == null) {
                note(notes, "열람실 CSV의 도서관을 LIB_STD에서 못 찾음: " + s(e.getValue().get(0), "도서관명"));
                continue;
            }
            // 휴관 요일은 종합자료실과 같게 본다 (note의 첫 구절이 원천 휴관일)
            String closedText = f.getNote() == null ? "" : f.getNote().split(" / ")[0];
            Set<Integer> closed = HourParser.parseClosedDays(closedText).days();
            List<Slot> weekday = new ArrayList<>();
            List<Slot> weekend = new ArrayList<>();
            for (Map<String, String> x : e.getValue()) {
                addIfPresent(weekday, HourParser.interval(s(x, "평일운영시작시각"), s(x, "평일운영종료시각")));
                addIfPresent(weekend, HourParser.interval(s(x, "주말운영시작시각"), s(x, "주말운영종료시각")));
            }
            List<Slot> wk = HourParser.mergeIntervals(weekday);
            List<Slot> we = HourParser.mergeIntervals(weekend);
            f.getSpaces().put(SpaceType.READING,
                    new SpaceData("열람실", HourParser.weekFrom(wk, we, we, closed)));
        }
    }

    // ------------------------------------------------------------ LIB_SMALL

    private static void libSmall(List<Map<String, String>> rows, Map<String, Integer> notes, List<SourceFacility> out) {
        // 이름 + 도로명 번지가 같은 행이 여럿이면 관리번호가 큰 행만 남긴다
        Map<List<String>, Map<String, String>> best = new HashMap<>();
        for (Map<String, String> r : rows) {
            List<String> rk = roadKey(s(r, "주소"));
            if (rk == null) {
                continue;
            }
            List<String> key = List.of(nospace(s(r, "도서관명")), rk.get(0), rk.get(1));
            Map<String, String> cur = best.get(key);
            if (cur == null || mgmtNo(r) > mgmtNo(cur)) {
                best.put(key, r);
            }
        }
        for (Map<String, String> r : rows) {
            List<String> rk = roadKey(s(r, "주소"));
            if (rk != null && best.get(List.of(nospace(s(r, "도서관명")), rk.get(0), rk.get(1))) != r) {
                note(notes, "LIB_SMALL 중복 행 제거(이름+도로명 번지 동일, 관리번호 큰 행 유지)");
                continue;
            }
            String name = s(r, "도서관명");
            String addr = s(r, "주소");
            SmallHours parsed = HourParser.parseSmall(s(r, "이용시간"), s(r, "휴관여부"));
            note(notes, "LIB_SMALL 시간 해석: " + (parsed.flag().isEmpty() ? "정상" : parsed.flag()));
            SourceFacility f = SourceFacility.builder()
                    .sourceType(SourceType.LIB_SMALL).sourceKey("SMALL-" + s(r, "작은도서관관리번호"))
                    .typeName(DefaultFacilityTypes.LIBRARY).name(name).address(addr)
                    .district(EtlUtil.districtOf(addr, s(r, "행정구명"))).accessType(AccessType.OPEN_HOURS)
                    .phone(EtlUtil.phone(s(r, "전화번호")))
                    .note(blankToNull(EtlUtil.truncate(s(r, "이용시간"), 255)))
                    .updated(EtlUtil.dateOrNull(s(r, "데이터기준일자")))
                    .lat(EtlUtil.toDouble(s(r, "위도"))).lng(EtlUtil.toDouble(s(r, "경도")))
                    .build();
            f.getSpaces().put(SpaceType.DEFAULT, new SpaceData("시설 전체", parsed.hours()));
            out.add(f);
        }
    }

    private static List<String> roadKey(String address) {
        Matcher m = ROAD_KEY.matcher(address.replaceAll("\\(.*?\\)", ""));
        return m.find() ? List.of(m.group(1), m.group(2)) : null;
    }

    private static long mgmtNo(Map<String, String> r) {
        String digits = s(r, "작은도서관관리번호").replaceAll("\\D", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }

    // ------------------------------------------------------------ PUBLIC_OPEN

    private static void publicOpen(List<Map<String, String>> rows, List<SourceFacility> out) {
        for (Map<String, String> r : rows) {
            String place = s(r, "개방장소명");
            String name = s(r, "개방시설명");
            String road = s(r, "소재지도로명주소");
            String addr = road.isEmpty() ? s(r, "소재지지번주소") : road;
            Set<Integer> closed = HourParser.parseClosedDays(s(r, "휴관일")).days();
            Slot weekday = HourParser.interval(s(r, "평일운영시작시각"), s(r, "평일운영종료시각"));
            Slot weekend = HourParser.interval(s(r, "주말운영시작시각"), s(r, "주말운영종료시각"));
            Map<Integer, List<Slot>> hours = HourParser.weekFrom(one(weekday), one(weekend), one(weekend), closed);
            String closedNote = s(r, "휴관일");
            SourceFacility f = SourceFacility.builder()
                    .sourceType(SourceType.PUBLIC_OPEN).sourceKey("OPEN-" + EtlUtil.md5Hex12(place + "|" + name))
                    .typeName(DefaultFacilityTypes.SPORTS).name(name).address(addr)
                    .district(EtlUtil.districtOf(addr, s(r, "소재지지번주소")))
                    // 예약제 여부는 원천에 없어 OPEN_HOURS로 둔다 (RESERVATION 구분은 미정)
                    .accessType(AccessType.OPEN_HOURS)
                    .phone(EtlUtil.phone(s(r, "사용안내전화번호")))
                    .note(closedNote.equals("연중무휴") ? null : blankToNull(closedNote))
                    .updated(EtlUtil.dateOrNull(s(r, "데이터기준일자")))
                    .lat(EtlUtil.toDouble(s(r, "위도"))).lng(EtlUtil.toDouble(s(r, "경도")))
                    .build();
            f.getSpaces().put(SpaceType.DEFAULT, new SpaceData("시설 전체", hours));
            out.add(f);
        }
    }

    // ------------------------------------------------------------ 공통

    private static List<Slot> one(Slot slot) {
        return slot == null ? null : List.of(slot);
    }

    private static void addIfPresent(List<Slot> list, Slot slot) {
        if (slot != null) {
            list.add(slot);
        }
    }

    private static void note(Map<String, Integer> notes, String message) {
        notes.merge(message, 1, Integer::sum);
    }
}
