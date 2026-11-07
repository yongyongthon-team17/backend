package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.DayState;
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
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A02 시간대별 시설 검색. 조회는 4회 고정(유형 확인, 시설, 공간, 운영시간)이며 판정은 메모리에서 한다. */
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
            return new FacilitySearchResponse(criteria, List.of(), 0, 0, 0);
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

        // 3) 공간별 판정
        List<Item> items = new ArrayList<>();
        int matchedSpaces = 0;
        int unknownSpaces = 0;
        for (Facility f : facilities) {
            Map<SpaceType, FacilitySpace> facilitySpaces = spaces.getOrDefault(f.getFacilityId(), Map.of());
            Map<SpaceType, List<OperatingHour>> facilityHours = hours.getOrDefault(f.getFacilityId(), Map.of());
            List<SpaceAvailability> available = new ArrayList<>();

            if (f.getAccessType() == AccessType.ALWAYS_OPEN) {
                // 상시 이용: 운영시간 행이 없어도 UNKNOWN이 아니다. DEFAULT 공간 하나로 표현
                FacilitySpace def = facilitySpaces.get(SpaceType.DEFAULT);
                String label = def != null ? def.getSpaceName() : DEFAULT_SPACE_LABEL;
                available.add(space(SpaceType.DEFAULT, label, dayOfWeek,
                        List.of(new TimeInterval(TimeText.format(0), TimeText.format(1440))),
                        "ACCESS_TYPE", false));
            } else {
                // enum 선언 순서(GENERAL → READING → DEFAULT)가 곧 정렬 순서
                for (SpaceType type : SpaceType.values()) {
                    FacilitySpace sp = facilitySpaces.get(type);
                    if (sp == null) {
                        continue;
                    }
                    List<Span> open = OperatingHours.openSpans(facilityHours.getOrDefault(type, List.of()));
                    if (open == null) {
                        // 행 없음 / UNKNOWN: 휴관으로 보지 않고 제외 + 집계
                        if (hasUnknownOrNoRows(facilityHours.getOrDefault(type, List.of()))) {
                            unknownSpaces++;
                        }
                        continue; // CLOSED만 있으면 집계 없이 제외
                    }
                    if (TimeCoverage.covers(open, start, end)) {
                        available.add(space(type, sp.getSpaceName(), dayOfWeek, OperatingHours.toIntervals(open),
                                "WEEKLY_SCHEDULE",
                                f.getAccessType() == AccessType.RESERVATION));
                    }
                }
            }

            if (!available.isEmpty()) {
                matchedSpaces += available.size();
                items.add(toItem(f, available));
            }
        }
        return new FacilitySearchResponse(criteria, items, items.size(), matchedSpaces, unknownSpaces);
    }

    /** 행이 없거나, UNKNOWN이거나, OPEN인데 시각이 비어 있으면 true. CLOSED뿐이면 false. */
    private static boolean hasUnknownOrNoRows(List<OperatingHour> rows) {
        if (rows.isEmpty()) {
            return true;
        }
        return rows.stream().anyMatch(h -> h.getDayState() != DayState.CLOSED);
    }

    private static SpaceAvailability space(SpaceType type, String label, int day, List<TimeInterval> intervals,
                                           String basis, boolean requiresReservation) {
        return new SpaceAvailability(type.name(), label, new DaySchedule(day, "OPEN", intervals),
                new Availability("AVAILABLE", basis, requiresReservation, List.of()));
    }

    private static Item toItem(Facility f, List<SpaceAvailability> available) {
        return new Item(String.valueOf(f.getFacilityId()), f.getType().getTypeId(), f.getType().getName(),
                f.getDistrict(), f.getAccessType().name(), f.getName(), f.getAddress(),
                f.getType().getDefaultImage(), new Coordinates(f.getLatitude(), f.getLongitude()),
                null, available);
    }
}
