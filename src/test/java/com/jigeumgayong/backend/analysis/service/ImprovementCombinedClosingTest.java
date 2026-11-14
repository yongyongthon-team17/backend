package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** 동종 시설 비교와 유동인구 비교를 모두 켠 기본 설정에서, 마감 비교가 LLM 입력에 관찰 하나로 들어가는지 확인한다. */
@SpringBootTest
@Import(FakeLlmConfig.class)
class ImprovementCombinedClosingTest {

    static final int TUE = 1;

    @Autowired ImprovementAnalysisService service;
    @Autowired FakeLlmClient llm;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    FacilityType library;
    int seq = 0;
    List<String> inputs;

    @BeforeEach
    void setUp() {
        llm.reset();
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        library = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        inputs = new ArrayList<>();
        llm.responder = input -> {
            inputs.add(input);
            return FakeLlmClient.validResponse(input);
        };
    }

    private Facility facility(int closeMin) {
        Facility f = facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(AccessType.OPEN_HOURS).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) closeMin).build());
        return f;
    }

    @Test
    void 마감_비교는_LLM_입력에_하나의_관찰로_들어가고_제안이_생성된다() {
        for (int i = 0; i < 6; i++) {
            facility(1320); // 같은 구·같은 유형 6곳이 22:00까지
        }
        Facility target = facility(1080); // 18:00 마감, 희망 10~12시에는 운영 중

        ImprovementItem item = service.analyze(String.valueOf(target.getFacilityId()), null, null, null, TUE,
                "10:00", "12:00").data().items().get(0);

        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(inputs).hasSize(1);
        String facts = inputs.get(0);
        assertThat(facts).contains("COMPARED_CLOSING").contains("경기도 수지구 유동인구 통계에서도 22:00까지")
                .contains("중앙값이 22:00");
        assertThat(facts).doesNotContain("PEER_LATER_CLOSING").doesNotContain("FLOW_CLOSING");
        assertThat(facts.split("\"type\":\"")).hasSize(2); // 관찰이 하나뿐이다
    }
}
