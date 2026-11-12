package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.TimeInterval;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer.Normalized;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A04 분석 대상 계산: 지정한 시설의 모든 공간을 희망 시간에 이용 가능한지와 관계없이 돌려준다.
 * 공간마다 이용 상태(AVAILABLE/UNAVAILABLE/UNKNOWN)는 A02와 같은 OperatingHours/TimeCoverage 판정을 재사용한다.
 * 공간 순서는 GENERAL → READING → DEFAULT로 고정(반복 요청 시 같은 순서 보장).
 */
@Service
@RequiredArgsConstructor
public class ImprovementCandidateFinder {

    private static final String DEFAULT_LABEL = "시설 전체";

    /**
     * 분석 대상 공간 한 건.
     *
     * @param currentCloseMin 현재 마지막 마감(분). 휴관(CLOSED)·운영시간 모름(UNKNOWN)이면 null
     * @param extendable      빈틈이 있고 마감만 희망 종료까지 늘리면 해결되는가 (점심 휴게·늦은 개장·이미 이용 가능이면 false)
     */
    public record Candidate(Facility facility, SpaceType spaceType, String spaceLabel,
                            DaySchedule selectedSchedule, Availability availability,
                            boolean requiresReservation, Integer currentCloseMin, boolean extendable,
                            int dayOfWeek, int startMin, int endMin) {
    }

    public record CandidateResult(List<Candidate> candidates, int unknownSpaceCount) {
    }

    private final FacilityRepository facilityRepository;
    private final FacilitySpaceRepository spaceRepository;
    private final OperatingHourRepository hourRepository;

    /**
     * 요청 facilityId 한 시설의 후보 계산. criteria 중 keyword·district·typeIds는 쓰지 않고 요일·시간만 쓴다.
     * 시설이 없으면 404 FACILITY_NOT_FOUND (A03과 같은 방식).
     */
    @Transactional(readOnly = true)
    public CandidateResult findForFacility(long facilityId, Normalized n, int dayOfWeek) {
        Facility f = facilityRepository.findDetailById(facilityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND,
                        ErrorCode.FACILITY_NOT_FOUND.defaultMessage(), List.of()));
        return candidatesOf(List.of(f), n, dayOfWeek);
    }

    private CandidateResult candidatesOf(List<Facility> facilities, Normalized n, int dayOfWeek) {
        if (facilities.isEmpty()) {
            return new CandidateResult(List.of(), 0);
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

        List<Candidate> candidates = new ArrayList<>();
        int unknown = 0;
        int start = n.startMin();
        int end = n.endMin();
        for (Facility f : facilities) {
            boolean reservation = f.getAccessType() == AccessType.RESERVATION;
            Map<SpaceType, FacilitySpace> facilitySpaces = spaces.getOrDefault(f.getFacilityId(), Map.of());
            if (f.getAccessType() == AccessType.ALWAYS_OPEN) {
                // 상시 이용: 운영시간 행이 없어도 UNKNOWN이 아니다. DEFAULT 공간 하나, 항상 이용 가능
                FacilitySpace def = facilitySpaces.get(SpaceType.DEFAULT);
                DaySchedule always = new DaySchedule(dayOfWeek, "OPEN",
                        List.of(new TimeInterval(TimeText.format(0), TimeText.format(1440))));
                candidates.add(new Candidate(f, SpaceType.DEFAULT, def != null ? def.getSpaceName() : DEFAULT_LABEL,
                        always, new Availability("AVAILABLE", "ACCESS_TYPE", false, List.of()),
                        false, 1440, false, dayOfWeek, start, end));
                continue;
            }
            Map<SpaceType, List<OperatingHour>> facilityHours = hours.getOrDefault(f.getFacilityId(), Map.of());
            for (SpaceType type : SpaceType.values()) {
                FacilitySpace sp = facilitySpaces.get(type);
                if (sp == null) {
                    continue;
                }
                List<OperatingHour> rows = facilityHours.getOrDefault(type, List.of());
                DaySchedule schedule = OperatingHours.daySchedule(dayOfWeek, rows);
                Availability availability = OperatingHours.availability(schedule, rows, start, end, reservation);
                Integer currentClose = null;
                boolean extendable = false;
                switch (schedule.state()) {
                    case "UNKNOWN" -> unknown++; // 휴관으로 추측하지 않는다. 분석에서는 '운영시간 정보 없음'으로 다룬다
                    case "OPEN" -> {
                        List<Span> open = OperatingHours.openSpans(rows);
                        currentClose = open.stream().mapToInt(Span::close).max().orElseThrow();
                        extendable = !"AVAILABLE".equals(availability.status())
                                && isExtendable(open, currentClose, start, end);
                    }
                    default -> { /* CLOSED: 현재 마감 없음 */ }
                }
                candidates.add(new Candidate(f, type, sp.getSpaceName(), schedule, availability, reservation,
                        currentClose, extendable, dayOfWeek, start, end));
            }
        }
        return new CandidateResult(candidates, unknown);
    }

    /** 한 시설의 공간별 요일(0=월 … 6=일) 7개 스케줄. 요일 편차·정기 휴관 같은 관찰 계산에 쓴다. */
    @Transactional(readOnly = true)
    public Map<SpaceType, List<DaySchedule>> weeklyHours(long facilityId) {
        Map<SpaceType, Map<Integer, List<OperatingHour>>> rows = new EnumMap<>(SpaceType.class);
        for (OperatingHour h : hourRepository.findAllByFacilityId(facilityId)) {
            rows.computeIfAbsent(h.getId().getSpaceType(), k -> new HashMap<>())
                    .computeIfAbsent(h.getId().getDayOfWeek().intValue(), k -> new ArrayList<>())
                    .add(h);
        }
        Map<SpaceType, List<DaySchedule>> weekly = new EnumMap<>(SpaceType.class);
        for (FacilitySpace s : spaceRepository.findByIdFacilityIdIn(List.of(facilityId))) {
            SpaceType type = s.getId().getSpaceType();
            Map<Integer, List<OperatingHour>> byDay = rows.getOrDefault(type, Map.of());
            List<DaySchedule> week = new ArrayList<>();
            for (int d = 0; d < 7; d++) {
                week.add(OperatingHours.daySchedule(d, byDay.getOrDefault(d, List.of())));
            }
            weekly.put(type, week);
        }
        return weekly;
    }

    /** 마지막 마감을 희망 종료까지 늘리면 빈틈이 전부 사라지는가. */
    private static boolean isExtendable(List<Span> open, int lastClose, int start, int end) {
        if (lastClose >= end) {
            return false;
        }
        List<Span> extended = new ArrayList<>(open);
        extended.add(new Span(lastClose, end));
        return TimeCoverage.covers(extended, start, end);
    }
}
