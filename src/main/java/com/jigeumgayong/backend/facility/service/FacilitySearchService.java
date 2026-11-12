package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Criteria;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Item;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.SpaceAvailability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer.Normalized;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A02 시간대별 시설 검색. 조건에 맞는 모든 시설을 이용 상태와 함께 돌려준다.
 * 조회는 4회 고정(유형 확인, 시설, 공간, 운영시간)이며 판정은 메모리에서 한다.
 */
@Service
@RequiredArgsConstructor
public class FacilitySearchService {

    private static final String DEFAULT_SPACE_LABEL = "시설 전체";

    private final FacilityRepository facilityRepository;
    private final FacilitySpaceRepository spaceRepository;
    private final OperatingHourRepository hourRepository;
    private final SearchCriteriaNormalizer normalizer;

    @Transactional(readOnly = true)
    public FacilitySearchResponse search(String keyword, String district, List<Integer> typeIds,
                                         int dayOfWeek, String startText, String endText) {
        // 1) 요청 검증·정규화 (A04와 공통)
        Normalized n = normalizer.normalize(keyword, district, typeIds, dayOfWeek, startText, endText);
        Criteria criteria = n.criteria();
        int start = n.startMin();
        int end = n.endMin();
        List<Short> shortTypeIds = n.typeIds();

        // 2) 후보 시설 + 공간 + 선택 요일 운영시간을 한꺼번에 조회
        List<Facility> facilities = facilityRepository.search(
                district == null, district == null ? "" : district,
                shortTypeIds.isEmpty(), shortTypeIds.isEmpty() ? List.of((short) -1) : shortTypeIds,
                n.keywordPattern());
        if (facilities.isEmpty()) {
            return new FacilitySearchResponse(criteria, List.of(), 0, 0, 0, 0);
        }
        List<Long> ids = facilities.stream().map(Facility::getFacilityId).toList();

        Map<Long, Map<SpaceType, FacilitySpace>> spaces = new HashMap<>();
        for (FacilitySpace s : spaceRepository.findByIdFacilityIdIn(ids)) {
            spaces.computeIfAbsent(s.getId().getFacilityId(), k -> new EnumMap<>(SpaceType.class))
                    .put(s.getId().getSpaceType(), s);
        }
        Map<Long, Map<SpaceType, List<OperatingHour>>> hours = new HashMap<>();
        for (OperatingHour h : hourRepository.findByFacilityIdsAndDay(ids, (short) dayOfWeek)) {
            hours.computeIfAbsent(h.getId().getFacilityId(), k -> new EnumMap<>(SpaceType.class))
                    .computeIfAbsent(h.getId().getSpaceType(), k -> new ArrayList<>())
                    .add(h);
        }

        // 3) 시설·공간별 판정: 이용 가능/불가/확인 필요를 모두 담는다
        List<Item> items = new ArrayList<>();
        int availableFacilities = 0;
        int matchedSpaces = 0;
        int unknownSpaces = 0;
        for (Facility f : facilities) {
            Map<SpaceType, FacilitySpace> facilitySpaces = spaces.getOrDefault(f.getFacilityId(), Map.of());
            Map<SpaceType, List<OperatingHour>> facilityHours = hours.getOrDefault(f.getFacilityId(), Map.of());
            boolean reservation = f.getAccessType() == AccessType.RESERVATION;
            List<SpaceAvailability> all = new ArrayList<>();

            if (f.getAccessType() == AccessType.ALWAYS_OPEN) {
                // 상시 이용: 운영시간 행이 없어도 UNKNOWN이 아니다. DEFAULT 공간 하나로 표현
                FacilitySpace def = facilitySpaces.get(SpaceType.DEFAULT);
                String label = def != null ? def.getSpaceName() : DEFAULT_SPACE_LABEL;
                all.add(new SpaceAvailability(SpaceType.DEFAULT.name(), label,
                        new DaySchedule(dayOfWeek, "OPEN",
                                List.of(new TimeInterval(TimeText.format(0), TimeText.format(1440)))),
                        new Availability("AVAILABLE", "ACCESS_TYPE", false, List.of())));
            } else {
                // enum 선언 순서(GENERAL → READING → DEFAULT)가 곧 정렬 순서
                for (SpaceType type : SpaceType.values()) {
                    FacilitySpace sp = facilitySpaces.get(type);
                    if (sp == null) {
                        continue;
                    }
                    List<OperatingHour> rows = facilityHours.getOrDefault(type, List.of());
                    DaySchedule schedule = OperatingHours.daySchedule(dayOfWeek, rows);
                    Availability availability = OperatingHours.availability(schedule, rows, start, end, reservation);
                    if ("UNKNOWN".equals(availability.status())) {
                        unknownSpaces++; // 행 없음/UNKNOWN: 휴관으로 보지 않고 확인 필요로 표시
                    }
                    all.add(new SpaceAvailability(type.name(), sp.getSpaceName(), schedule, availability));
                }
            }

            String status = facilityStatus(all);
            if ("AVAILABLE".equals(status)) {
                availableFacilities++;
            }
            matchedSpaces += (int) all.stream().filter(x -> "AVAILABLE".equals(x.availability().status())).count();
            items.add(toItem(f, status, all));
        }
        // 이용 가능 → 이용 불가 → 확인 필요 순. 같은 상태 안에서는 조회 순서(facilityId 오름차순)를 유지한다(안정 정렬)
        items.sort(Comparator.comparingInt(x -> statusRank(x.availabilityStatus())));
        return new FacilitySearchResponse(criteria, items, items.size(), availableFacilities, matchedSpaces,
                unknownSpaces);
    }

    /** 공간 중 하나라도 이용 가능하면 AVAILABLE, 아니면 닫힌 공간이 있으면 UNAVAILABLE, 전부 모르면(공간 없음 포함) UNKNOWN */
    private static String facilityStatus(List<SpaceAvailability> spaces) {
        if (spaces.stream().anyMatch(x -> "AVAILABLE".equals(x.availability().status()))) {
            return "AVAILABLE";
        }
        if (spaces.stream().anyMatch(x -> "UNAVAILABLE".equals(x.availability().status()))) {
            return "UNAVAILABLE";
        }
        return "UNKNOWN";
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "AVAILABLE" -> 0;
            case "UNAVAILABLE" -> 1;
            default -> 2;
        };
    }

    private static Item toItem(Facility f, String status, List<SpaceAvailability> spaces) {
        return new Item(String.valueOf(f.getFacilityId()), f.getType().getTypeId(), f.getType().getName(),
                f.getDistrict(), f.getAccessType().name(), f.getName(), f.getAddress(),
                f.getType().getDefaultImage(), new Coordinates(f.getLatitude(), f.getLongitude()),
                null, status, spaces);
    }
}
