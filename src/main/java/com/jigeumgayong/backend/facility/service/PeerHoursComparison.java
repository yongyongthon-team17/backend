package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.facility.service.TimeCoverage.Span;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 행정구·같은 유형의 다른 시설과 선택 요일 운영시간을 비교한다(AI 개선안의 근거 사실용).
 * 시설 판정은 A02와 같은 규칙(공간 하나가 희망 구간을 빈틈없이 덮으면 운영)을 쓴다. DB만 읽고 외부 호출은 없다.
 */
@Service
@RequiredArgsConstructor
public class PeerHoursComparison {

    /**
     * @param peerCount        비교 대상 시설 수(자기 자신 제외)
     * @param peersOpen        희망 시간에 운영하는 시설 수
     * @param closeSampleCount 선택 요일에 마감 시각을 알 수 있는 시설 수(중앙값 계산에 쓴 수)
     * @param medianCloseMin   그 시설들의 마지막 마감 시각 중앙값(분). 표본이 없으면 null
     */
    public record Result(int peerCount, int peersOpen, int closeSampleCount, Integer medianCloseMin) {
    }

    private final FacilityRepository facilityRepository;
    private final FacilitySpaceRepository spaceRepository;
    private final OperatingHourRepository hourRepository;

    @Transactional(readOnly = true)
    public Result compare(Facility facility, int dayOfWeek, int startMin, int endMin) {
        List<Facility> peers = facilityRepository.search(false, facility.getDistrict(), false,
                        List.of(facility.getType().getTypeId()), "%").stream()
                .filter(p -> !p.getFacilityId().equals(facility.getFacilityId())).toList();
        if (peers.isEmpty()) {
            return new Result(0, 0, 0, null);
        }
        List<Long> ids = peers.stream().map(Facility::getFacilityId).toList();
        Map<Long, List<SpaceType>> spaces = new HashMap<>();
        for (FacilitySpace s : spaceRepository.findByIdFacilityIdIn(ids)) {
            spaces.computeIfAbsent(s.getId().getFacilityId(), k -> new ArrayList<>()).add(s.getId().getSpaceType());
        }
        Map<Long, Map<SpaceType, List<OperatingHour>>> hours = new HashMap<>();
        for (OperatingHour h : hourRepository.findByFacilityIdsAndDay(ids, (short) dayOfWeek)) {
            hours.computeIfAbsent(h.getId().getFacilityId(), k -> new EnumMap<>(SpaceType.class))
                    .computeIfAbsent(h.getId().getSpaceType(), k -> new ArrayList<>()).add(h);
        }

        int open = 0;
        List<Integer> closes = new ArrayList<>();
        for (Facility p : peers) {
            if (p.getAccessType() == AccessType.ALWAYS_OPEN) {
                open++; // 상시 이용: 항상 운영, 마감 시각은 비교에서 뺀다
                continue;
            }
            boolean covers = false;
            int lastClose = -1;
            Map<SpaceType, List<OperatingHour>> bySpace = hours.getOrDefault(p.getFacilityId(), Map.of());
            for (SpaceType type : spaces.getOrDefault(p.getFacilityId(), List.of())) {
                List<OperatingHour> rows = bySpace.getOrDefault(type, List.of());
                if (!"OPEN".equals(OperatingHours.daySchedule(dayOfWeek, rows).state())) {
                    continue; // 휴관·운영시간 모름 공간은 운영도 마감도 세지 않는다
                }
                List<Span> spans = OperatingHours.openSpans(rows);
                covers |= TimeCoverage.covers(spans, startMin, endMin);
                for (Span s : spans) {
                    lastClose = Math.max(lastClose, s.close());
                }
            }
            if (covers) {
                open++;
            }
            if (lastClose >= 0) {
                closes.add(lastClose);
            }
        }
        Integer median = null;
        if (!closes.isEmpty()) {
            Collections.sort(closes);
            median = closes.get(closes.size() / 2);
        }
        return new Result(peers.size(), open, closes.size(), median);
    }
}
