package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** app.analysis.transport-link.enabled=false: 정류장 수가 있어도 NOT_ENABLED이고 LLM 입력에도 넣지 않는다. */
@SpringBootTest(properties = "app.analysis.transport-link.enabled=false")
@Import(FakeLlmConfig.class)
class ImprovementTransportSwitchOffTest {

    @Autowired ImprovementAnalysisService service;
    @Autowired FakeLlmClient llm;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    @Test
    void 스위치가_꺼지면_NOT_ENABLED이고_A04는_정상_응답한다() {
        llm.reset();
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        Facility f = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("off1").district("수지구").accessType(AccessType.OPEN_HOURS).name("시설")
                .address("용인시").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1"))
                .busStopCount(4).build());
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) 1080).build());
        List<String> inputs = new CopyOnWriteArrayList<>();
        llm.responder = facts -> {
            inputs.add(facts);
            return FakeLlmClient.validResponse(facts);
        };

        ImprovementItem item = service.analyze(String.valueOf(f.getFacilityId()), null, null, null, 1,
                "19:00", "21:00").data().items().get(0);

        assertThat(item.transport().status().name()).isEqualTo("NOT_ENABLED");
        assertThat(item.transport().stopCount()).isNull();
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(item.integratedSuggestion().status()).isEqualTo("PARTIAL");
        assertThat(inputs.get(0)).doesNotContain("busStop");

        ImprovementItem hit = service.analyze(String.valueOf(f.getFacilityId()), null, null, null, 1,
                "19:00", "21:00").data().items().get(0);
        assertThat(hit.cache().status()).isEqualTo("HIT");
        assertThat(hit.integratedSuggestion().status()).isEqualTo("PARTIAL");
    }
}
