package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.District;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse.DayOption;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse.FacilityTypeOption;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.global.response.Meta;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilitySearchOptionService {

    private static final List<String> DAY_LABELS = List.of("월", "화", "수", "목", "금", "토", "일");

    private final FacilityTypeRepository facilityTypeRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public FacilitySearchOptionsResponse getOptions() {
        // typeId는 하드코딩하지 않고 DB에서 읽는다. 비어 있어도 정상(빈 배열).
        List<FacilityTypeOption> types = facilityTypeRepository.findAll(Sort.by("typeId")).stream()
                .map(t -> new FacilityTypeOption(t.getTypeId(), t.getName(), t.getDefaultImage()))
                .toList();

        List<DayOption> days = IntStream.range(0, DAY_LABELS.size())
                .mapToObj(i -> new DayOption(i, DAY_LABELS.get(i)))
                .toList();

        // 0=월 … 6=일. 기준 시간대는 Asia/Seoul (Clock에 지정).
        int today = LocalDate.now(clock).getDayOfWeek().getValue() - 1;

        return new FacilitySearchOptionsResponse(types, District.ALL, days, today, Meta.ZONE.getId());
    }
}