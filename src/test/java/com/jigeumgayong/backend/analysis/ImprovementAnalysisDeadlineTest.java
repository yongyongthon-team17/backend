package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService.AnalysisResult;
import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.support.FakeLlmClient;
import com.jigeumgayong.backend.support.FakeLlmConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 요청 전체 제한 시간(app.llm.request-deadline)을 넘으면 남은 항목은 UNAVAILABLE이다. */
@SpringBootTest(properties = { "app.llm.request-deadline=1s", "app.llm.max-concurrency=1" })
@Import(FakeLlmConfig.class)
class ImprovementAnalysisDeadlineTest {

    @Autowired ImprovementAnalysisService service;
    @Autowired FakeLlmClient llm;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    @BeforeEach
    void setUp() {
        llm.reset();
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        // 한 시설의 공간 3개(시설 하나가 가질 수 있는 최대)가 모두 19-21시에 닫혀 있다
        facility = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("dl").district("수지구").accessType(AccessType.OPEN_HOURS).name("시설")
                .address("주소").latitude(new BigDecimal("37.32")).longitude(new BigDecimal("127.1")).build());
        for (SpaceType t : SpaceType.values()) {
            spaceRepository.save(new FacilitySpace(facility.getFacilityId(), t, t.name()));
            hourRepository.save(OperatingHour.builder().facilityId(facility.getFacilityId()).spaceType(t)
                    .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                    .openMin((short) 540).closeMin((short) 1080).build());
        }
    }

    Facility facility;

    @Test
    void 전체_제한_시간을_넘으면_남은_항목은_UNAVAILABLE이고_응답이_지연되지_않는다() {
        llm.delayMillis = 3000; // 풀 크기 1 → 항목마다 3초, 전체 제한은 1초

        long start = System.nanoTime();
        AnalysisResult r = service.analyze(String.valueOf(facility.getFacilityId()), null, null, null, 1,
                "19:00", "21:00");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2500);
        assertThat(r.data().items()).hasSize(3)
                .allSatisfy(i -> assertThat(i.proposalStatus()).isEqualTo("UNAVAILABLE"));
        assertThat(r.data().items()).extracting(ImprovementItem::suggestionId).containsOnlyNulls();
        assertThat(r.warnings()).anyMatch(w -> w.code().equals("LLM_UNAVAILABLE"));
        assertThat(aiRepository.count()).isZero();
    }
}
