package com.jigeumgayong.backend.facility.etl;

import static com.jigeumgayong.backend.facility.etl.EtlUtil.nospace;
import static com.jigeumgayong.backend.facility.etl.EtlUtil.s;

import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.etl.ExtraSources.ParkingTag;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import com.jigeumgayong.backend.facility.etl.SeedData.AmenityAssignment;
import com.jigeumgayong.backend.facility.etl.SeedData.Quarantined;
import com.jigeumgayong.backend.global.response.Meta;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * classpath:seed/ 의 원천 CSV를 읽어 적재 대상({@link SeedData})을 만든다. DB는 건드리지 않는다.
 * 좌표 없음·용인시 범위 밖 행은 적재하지 않고 격리한다 (R16, (0,0) 가짜 좌표 금지).
 */
@Component
public class SeedBuilder {

    private static final double LAT_MIN = 37.0;
    private static final double LAT_MAX = 37.5;
    private static final double LNG_MIN = 126.9;
    private static final double LNG_MAX = 127.5;

    public SeedData build() {
        Map<String, Integer> notes = new TreeMap<>();
        List<SourceFacility> all = new ArrayList<>(CoreSources.build(
                CsvReader.read("lib_std.csv"), CsvReader.read("lib_small.csv"),
                CsvReader.read("reading_rooms.csv"), CsvReader.read("public_open.csv"), notes));
        all.addAll(ExtraSources.adminCenters(CsvReader.read("admin_centers.csv")));
        all.addAll(ExtraSources.manual(CsvReader.read("manual_facilities.csv")));

        applyGeoCache(all);

        List<SourceFacility> ok = new ArrayList<>();
        List<Quarantined> quarantine = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        for (SourceFacility f : all) {
            String why = invalidReason(f, usedKeys);
            if (why != null) {
                quarantine.add(new Quarantined(f.getSourceType(), f.getSourceKey(), f.getName(), f.getAddress(), why));
            } else {
                ok.add(f);
            }
        }
        List<String> errors = selfCheck(ok);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("데이터 규칙 위반:\n  " + String.join("\n  ", errors));
        }

        applyBusStops(ok, notes);
        List<AmenityAssignment> amenities = amenities(ok);
        return new SeedData(ok, quarantine, amenities, notes);
    }

    /**
     * seed/bus_stops.csv(용인 정류장)로 시설마다 반경 500m 정류장 수를 계산한다.
     * 파일이 없거나 비어 있으면 아무것도 채우지 않는다(null = 미확인, 0으로 대체하지 않음).
     */
    private void applyBusStops(List<SourceFacility> facilities, Map<String, Integer> notes) {
        BusStopCounter counter;
        try {
            counter = BusStopCounter.fromRows(CsvReader.read("bus_stops.csv"));
        } catch (UncheckedIOException e) {
            notes.put("정류장 파일 없음: 교통 정보를 계산하지 않음", facilities.size());
            return;
        }
        if (counter.isEmpty()) {
            notes.put("정류장 파일이 비어 있음: 교통 정보를 계산하지 않음", facilities.size());
            return;
        }
        LocalDate counted = LocalDate.now(Meta.ZONE);
        for (SourceFacility f : facilities) {
            f.setBusStopCount(counter.countWithin(f.getLat(), f.getLng()));
            f.setBusCountedAt(counted);
        }
        notes.put("교통 정보 계산", facilities.size());
    }

    /** 좌표가 없는 시설은 geo_cache.csv(주소 → 좌표)에서 채운다. 캐시에도 없으면 좌표 없음으로 격리된다. */
    private void applyGeoCache(List<SourceFacility> facilities) {
        Map<String, double[]> cache = new HashMap<>();
        for (Map<String, String> r : CsvReader.read("geo_cache.csv")) {
            Double lat = EtlUtil.toDouble(s(r, "latitude"));
            Double lng = EtlUtil.toDouble(s(r, "longitude"));
            if (lat != null && lng != null) {
                cache.put(s(r, "address"), new double[] {lat, lng});
            }
        }
        for (SourceFacility f : facilities) {
            if (f.getLat() != null || f.getAddress() == null || f.getAddress().isBlank()) {
                continue;
            }
            f.setDistrict(EtlUtil.districtOf(f.getAddress()));
            double[] hit = cache.get(EtlUtil.geoQuery(f.getAddress()));
            if (f.getDistrict() != null && hit != null) {
                f.setLat(hit[0]);
                f.setLng(hit[1]);
            }
        }
    }

    /** 격리 사유. null이면 적재 가능. 행정구는 주소로 판별한다 (R18). */
    private String invalidReason(SourceFacility f, Set<String> usedKeys) {
        if (f.getInvalidReason() != null) {
            return f.getInvalidReason();
        }
        if (f.getDistrict() == null && f.getAddress() != null) {
            f.setDistrict(EtlUtil.districtOf(f.getAddress()));
        }
        String why = null;
        if (f.getName() == null || f.getName().isBlank()) {
            why = "이름 없음";
        } else if (f.getAddress() == null || f.getAddress().isBlank()) {
            why = "주소 없음";
        } else if (f.getDistrict() == null) {
            why = "행정구 판별 실패";
        } else if (!DefaultFacilityTypes.isKnown(f.getTypeName())) {
            why = "알 수 없는 시설 유형: " + f.getTypeName();
        } else if (f.getSourceKey() == null || f.getSourceKey().isBlank()) {
            why = "source_key 없음";
        } else if (f.getLat() == null || f.getLng() == null) {
            why = "좌표 없음 (geo_cache.csv에 주소가 없음)";
        } else if (f.getLat() < LAT_MIN || f.getLat() > LAT_MAX || f.getLng() < LNG_MIN || f.getLng() > LNG_MAX) {
            why = "좌표 범위 밖 " + f.getLat() + "," + f.getLng();
        } else if (!usedKeys.add(f.getSourceType() + "|" + f.getSourceKey())) {
            why = "source_key 중복";
        }
        return why;
    }

    /** DB CHECK에 걸리기 전에 발견하는 데이터 규칙 점검: 같은 요일 구간 겹침·혼재, 길이 초과. */
    private List<String> selfCheck(List<SourceFacility> facilities) {
        List<String> errors = new ArrayList<>();
        for (SourceFacility f : facilities) {
            f.getSpaces().forEach((space, data) -> {
                if (data.hours() == null) {
                    return;
                }
                data.hours().forEach((day, slots) -> {
                    List<Slot> opens = slots.stream().filter(sl -> sl.state() == DayState.OPEN)
                            .sorted((a, b) -> Integer.compare(a.openMin(), b.openMin())).toList();
                    if (slots.size() > 1 && opens.size() != slots.size()) {
                        errors.add(f.getName() + "/" + space + "/" + day + ": OPEN 과 다른 상태 혼재");
                    }
                    for (int i = 1; i < opens.size(); i++) {
                        if (opens.get(i).openMin() < opens.get(i - 1).closeMin()) {
                            errors.add(f.getName() + "/" + space + "/" + day + ": 구간 겹침");
                        }
                    }
                });
            });
            if (f.getName().length() > 100 || f.getAddress().length() > 255) {
                errors.add(f.getName() + ": 길이 초과");
            }
        }
        return errors;
    }

    /** 부설주차장(이름 일치)과 도서관 대관(LIB_STD, 이름 일치) 태그. 일치하는 시설이 없으면 붙이지 않는다 (R01). */
    private List<AmenityAssignment> amenities(List<SourceFacility> ok) {
        List<AmenityAssignment> out = new ArrayList<>();
        for (ParkingTag tag : ExtraSources.parkingTags(CsvReader.read("parking.csv"))) {
            Set<String> names = new HashSet<>();
            tag.names().forEach(n -> names.add(nospace(n)));
            for (SourceFacility f : ok) {
                if (names.contains(nospace(f.getName()))) {
                    out.add(new AmenityAssignment(f.getSourceType(), f.getSourceKey(), AmenityCode.PARKING, tag.detail()));
                }
            }
        }
        ExtraSources.rentalDetails(CsvReader.read("library_rental.csv")).forEach((lib, detail) -> {
            for (SourceFacility f : ok) {
                if (f.getSourceType() == SourceType.LIB_STD && ExtraSources.sameName(f.getName(), lib)) {
                    out.add(new AmenityAssignment(f.getSourceType(), f.getSourceKey(), AmenityCode.RENTAL_ROOM, detail));
                }
            }
        });
        return out;
    }
}
