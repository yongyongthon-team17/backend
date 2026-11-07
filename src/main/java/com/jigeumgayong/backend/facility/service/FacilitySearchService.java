package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.District;
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
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final FacilityTypeRepository typeRepository;

    @Transactional(readOnly = true)
    public FacilitySearchResponse search(String keyword, String district, List<Integer> typeIds,
                                         int dayOfWeek, String startText, String endText) {
        // 1) 요청 검증: INVALID_REQUEST 계열을 먼저, 시간(INVALID_TIME_RANGE)은 그 다음
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw invalidRequest("dayOfWeek", ErrorDetail.OUT_OF_RANGE);
        }
        if (district != null && !District.isValid(district)) {
            throw invalidRequest("district", ErrorDetail.INVALID_VALUE);
        }
        List<Integer> distinctTypeIds = typeIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(typeIds));
        List<Short> shortTypeIds = validateTypeIds(distinctTypeIds);

        int[] range = TimeText.parseRange(startText, endText);
        int start = range[0];
        int end = range[1];

        String normalizedKeyword = normalizeKeyword(keyword);
        Criteria criteria = new Criteria(normalizedKeyword, district, distinctTypeIds, dayOfWeek,
                TimeText.format(start), TimeText.format(end));

        // 2) 후보 시설 + 공간 + 선택 요일 운영시간을 한꺼번에 조회
        List<Facility> facilities = facilityRepository.search(
                district == null, district == null ? "" : district,
                shortTypeIds.isEmpty(), shortTypeIds.isEmpty() ? List.of((short) -1) : shortTypeIds,
                likePattern(normalizedKeyword));
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
                    List<Span> open = openSpans(facilityHours.getOrDefault(type, List.of()));
                    if (open == null) {
                        // 행 없음 / UNKNOWN: 휴관으로 보지 않고 제외 + 집계
                        if (hasUnknownOrNoRows(facilityHours.getOrDefault(type, List.of()))) {
                            unknownSpaces++;
                        }
                        continue; // CLOSED만 있으면 집계 없이 제외
                    }
                    if (TimeCoverage.covers(open, start, end)) {
                        List<TimeInterval> intervals = open.stream()
                                .sorted(Comparator.comparingInt(Span::open))
                                .map(s -> new TimeInterval(TimeText.format(s.open()), TimeText.format(s.close())))
                                .toList();
                        available.add(space(type, sp.getSpaceName(), dayOfWeek, intervals, "WEEKLY_SCHEDULE",
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

    /**
     * OPEN 구간(유효한 시각 쌍)만 모아 돌려준다. OPEN이 하나도 없으면 null.
     * (DDL CHECK상 OPEN과 CLOSED/UNKNOWN은 같은 요일에 섞이지 않지만 방어적으로 OPEN을 우선한다.)
     */
    private static List<Span> openSpans(List<OperatingHour> rows) {
        List<Span> spans = new ArrayList<>();
        for (OperatingHour h : rows) {
            if (h.getDayState() == DayState.OPEN && h.getOpenMin() != null && h.getCloseMin() != null) {
                spans.add(new Span(h.getOpenMin(), h.getCloseMin()));
            }
        }
        return spans.isEmpty() ? null : spans;
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

    /** 존재하지 않는 typeId가 하나라도 있으면 400 (06 3.6). */
    private List<Short> validateTypeIds(List<Integer> typeIds) {
        List<Short> result = new ArrayList<>();
        for (Integer id : typeIds) {
            if (id == null || id <= 0 || id > Short.MAX_VALUE) {
                throw invalidRequest("facilityTypeIds", ErrorDetail.INVALID_VALUE);
            }
            result.add(id.shortValue());
        }
        if (!result.isEmpty()) {
            Set<Short> found = new LinkedHashSet<>();
            typeRepository.findAllById(result).forEach(t -> found.add(t.getTypeId()));
            if (found.size() != result.size()) {
                throw invalidRequest("facilityTypeIds", ErrorDetail.INVALID_VALUE);
            }
        }
        return result;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** LIKE 와일드카드(%, _)와 이스케이프 문자(\)를 글자 그대로 검색하도록 이스케이프한다. */
    private static String likePattern(String keyword) {
        if (keyword == null) {
            return "%";
        }
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static BusinessException invalidRequest(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail(field, reason)));
    }
}
