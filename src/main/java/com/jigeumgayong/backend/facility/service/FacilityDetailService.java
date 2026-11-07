package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.Criteria;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.FacilityDetail;
import com.jigeumgayong.backend.facility.dto.FacilityDetailResponse.SpaceDetail;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A03 시설 상세. 조회는 4회 고정(시설+유형, 공간, 운영시간, 편의시설)이며 판정은 메모리에서 한다. */
@Service
@RequiredArgsConstructor
public class FacilityDetailService {

    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9][0-9]*$");
    private static final String DEFAULT_SPACE_LABEL = "시설 전체";
    private static final String READING_TAG = "열람실";

    private final FacilityRepository facilityRepository;
    private final FacilitySpaceRepository spaceRepository;
    private final OperatingHourRepository hourRepository;
    private final FacilityAmenityRepository amenityRepository;

    @Transactional(readOnly = true)
    public FacilityDetailResponse detail(String facilityIdText, int dayOfWeek, String startText, String endText) {
        // 1) 요청 검증은 DB 접근 전에 끝낸다: ID 형식 → 요일 → 시간
        long facilityId = parseFacilityId(facilityIdText);
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw invalidRequest("dayOfWeek", ErrorDetail.OUT_OF_RANGE);
        }
        int[] range = TimeText.parseRange(startText, endText);
        String start = TimeText.format(range[0]);
        String end = TimeText.format(range[1]);

        // 2) 시설 → 공간 → 운영시간 → 편의시설 (시설이 없으면 여기서 404)
        Facility f = facilityRepository.findDetailById(facilityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND,
                        ErrorCode.FACILITY_NOT_FOUND.defaultMessage(), List.of()));
        Map<SpaceType, FacilitySpace> spaces = new EnumMap<>(SpaceType.class);
        for (FacilitySpace s : spaceRepository.findByIdFacilityIdIn(List.of(facilityId))) {
            spaces.put(s.getId().getSpaceType(), s);
        }
        // 공간 → 요일 → 구간 행
        Map<SpaceType, Map<Integer, List<OperatingHour>>> hours = new EnumMap<>(SpaceType.class);
        for (OperatingHour h : hourRepository.findAllByFacilityId(facilityId)) {
            hours.computeIfAbsent(h.getId().getSpaceType(), k -> new HashMap<>())
                    .computeIfAbsent(h.getId().getDayOfWeek().intValue(), k -> new ArrayList<>())
                    .add(h);
        }

        // 3) 공간별 상세. enum 선언 순서(GENERAL → READING → DEFAULT)가 정렬 순서
        boolean reservation = f.getAccessType() == AccessType.RESERVATION;
        List<SpaceDetail> details = new ArrayList<>();
        if (f.getAccessType() == AccessType.ALWAYS_OPEN) {
            // 상시 이용: 운영시간 행이 없어도 UNKNOWN이 아니다. DEFAULT 공간 하나로 표현
            FacilitySpace def = spaces.get(SpaceType.DEFAULT);
            details.add(alwaysOpenSpace(def != null ? def.getSpaceName() : DEFAULT_SPACE_LABEL, dayOfWeek));
        } else {
            for (SpaceType type : SpaceType.values()) {
                FacilitySpace sp = spaces.get(type);
                if (sp != null) {
                    details.add(weeklySpace(type, sp.getSpaceName(), hours.getOrDefault(type, Map.of()),
                            dayOfWeek, range[0], range[1], start, end, reservation));
                }
            }
        }

        FacilityDetail facility = new FacilityDetail(String.valueOf(f.getFacilityId()), f.getType().getTypeId(),
                f.getType().getName(), f.getDistrict(), f.getAccessType().name(), f.getName(), f.getAddress(),
                f.getType().getDefaultImage(), new Coordinates(f.getLatitude(), f.getLongitude()),
                f.getPhone(), f.getClosedDayNote(), f.getSourceUpdatedAt(),
                amenities(amenityRepository.findByIdFacilityId(facilityId), spaces.containsKey(SpaceType.READING)),
                details);
        return new FacilityDetailResponse(new Criteria(dayOfWeek, start, end), facility);
    }

    private static SpaceDetail alwaysOpenSpace(String label, int dayOfWeek) {
        List<DaySchedule> week = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            week.add(new DaySchedule(d, "OPEN", List.of(new TimeInterval(TimeText.format(0), TimeText.format(1440)))));
        }
        return new SpaceDetail(SpaceType.DEFAULT.name(), label, week.get(dayOfWeek),
                new Availability("AVAILABLE", "ACCESS_TYPE", false, List.of()), week);
    }

    private static SpaceDetail weeklySpace(SpaceType type, String label, Map<Integer, List<OperatingHour>> byDay,
                                           int dayOfWeek, int start, int end, String startText, String endText,
                                           boolean reservation) {
        List<DaySchedule> week = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            week.add(OperatingHours.daySchedule(d, byDay.getOrDefault(d, List.of())));
        }
        DaySchedule selected = week.get(dayOfWeek);

        // 선택 요일 판정. 운영 구간이 있어야만 갭 계산, 그 외는 상태로 결정
        Availability availability = switch (selected.state()) {
            case "OPEN" -> {
                List<Span> spans = OperatingHours.openSpans(byDay.getOrDefault(dayOfWeek, List.of()));
                List<Gap> gaps = TimeCoverage.findGaps(spans, start, end).stream()
                        .map(g -> new Gap(TimeText.format(g.open()), TimeText.format(g.close())))
                        .toList();
                yield new Availability(gaps.isEmpty() ? "AVAILABLE" : "UNAVAILABLE", "WEEKLY_SCHEDULE",
                        reservation, gaps);
            }
            // 고정 휴관: 요청 전체가 미운영
            case "CLOSED" -> new Availability("UNAVAILABLE", "WEEKLY_SCHEDULE", reservation,
                    List.of(new Gap(startText, endText)));
            // 행 없음/UNKNOWN: 휴관으로 바꾸지 않는다
            default -> new Availability("UNKNOWN", "WEEKLY_SCHEDULE", reservation, null);
        };
        return new SpaceDetail(type.name(), label, selected, availability, week);
    }

    /** detail 문구가 있으면 그대로, 없으면 코드 라벨. READING 공간이 있으면 '열람실'을 맨 앞에. */
    private static List<String> amenities(List<FacilityAmenity> rows, boolean hasReading) {
        List<String> tags = new ArrayList<>();
        if (hasReading) {
            tags.add(READING_TAG);
        }
        rows.stream()
                .sorted(Comparator.comparing(a -> a.getId().getAmenityCode()))
                .forEach(a -> tags.add(a.getDetail() != null && !a.getDetail().isBlank()
                        ? a.getDetail() : label(a.getId().getAmenityCode())));
        return tags;
    }

    private static String label(AmenityCode code) {
        return switch (code) {
            case PARKING -> "주차 가능";
            case RENTAL_ROOM -> "대관 가능";
        };
    }

    /** 10진수 양수 문자열이고 Long 범위 안이어야 한다. 아니면 400 (07 3.5). */
    private static long parseFacilityId(String text) {
        if (text == null || !POSITIVE_INT.matcher(text).matches()) {
            throw invalidRequest("facilityId", ErrorDetail.INVALID_FORMAT);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalidRequest("facilityId", ErrorDetail.OUT_OF_RANGE);
        }
    }

    private static BusinessException invalidRequest(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail(field, reason)));
    }
}
