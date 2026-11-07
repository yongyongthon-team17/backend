package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Gap;
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

/**
 * A04 후보 계산: 희망 시간에 닫혀 있는(UNAVAILABLE) 공간을 시설×공간 단위로 찾는다.
 * A02와 같은 3쿼리 + OperatingHours/TimeCoverage 판정을 재사용한다.
 * 결과 순서는 facilityId 오름차순, 공간은 GENERAL → READING → DEFAULT로 고정(반복 요청 시 같은 순서 보장).
 */
@Service
@RequiredArgsConstructor
public class ImprovementCandidateFinder {

    /**
     * 개선 후보 한 건.
     *
     * @param currentCloseMin 현재 마지막 마감(분). 휴관(CLOSED)이면 null
     * @param extendable      마감만 희망 종료까지 늘리면 해결되는가 (점심 휴게·늦은 개장이면 false)
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

    @Transactional(readOnly = true)
    public CandidateResult find(Normalized n, int dayOfWeek) {
        List<Facility> facilities = facilityRepository.search(
                n.criteria().district() == null, n.criteria().district() == null ? "" : n.criteria().district(),
                n.typeIds().isEmpty(), n.typeIds().isEmpty() ? List.of((short) -1) : n.typeIds(),
                n.keywordPattern());
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
            if (f.getAccessType() == AccessType.ALWAYS_OPEN) {
                continue; // 일내 운영시간 불일치가 없으므로 후보도 unknown 집계도 아니다
            }
            boolean reservation = f.getAccessType() == AccessType.RESERVATION;
            Map<SpaceType, FacilitySpace> facilitySpaces = spaces.getOrDefault(f.getFacilityId(), Map.of());
            Map<SpaceType, List<OperatingHour>> facilityHours = hours.getOrDefault(f.getFacilityId(), Map.of());
            for (SpaceType type : SpaceType.values()) {
                FacilitySpace sp = facilitySpaces.get(type);
                if (sp == null) {
                    continue;
                }
                DaySchedule schedule = OperatingHours.daySchedule(dayOfWeek,
                        facilityHours.getOrDefault(type, List.of()));
                switch (schedule.state()) {
                    case "UNKNOWN" -> unknown++; // 휴관으로 추측하지 않고 제외 수로 알린다
                    case "CLOSED" -> candidates.add(new Candidate(f, type, sp.getSpaceName(), schedule,
                            new Availability("UNAVAILABLE", "WEEKLY_SCHEDULE", reservation,
                                    List.of(new Gap(TimeText.format(start), TimeText.format(end)))),
                            reservation, null, false, dayOfWeek, start, end));
                    default -> {
                        List<Span> open = OperatingHours.openSpans(facilityHours.get(type));
                        List<Span> gaps = TimeCoverage.findGaps(open, start, end);
                        if (gaps.isEmpty()) {
                            break; // AVAILABLE: 과거 개선안을 보여줄 대상이 아니다
                        }
                        int lastClose = open.stream().mapToInt(Span::close).max().orElseThrow();
                        candidates.add(new Candidate(f, type, sp.getSpaceName(), schedule,
                                new Availability("UNAVAILABLE", "WEEKLY_SCHEDULE", reservation,
                                        gaps.stream().map(g -> new Gap(TimeText.format(g.open()),
                                                TimeText.format(g.close()))).toList()),
                                reservation, lastClose, isExtendable(open, lastClose, start, end),
                                dayOfWeek, start, end));
                    }
                }
            }
        }
        return new CandidateResult(candidates, unknown);
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
