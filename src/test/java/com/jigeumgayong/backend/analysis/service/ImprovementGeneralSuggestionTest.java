package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService.AnalysisResult;
import com.jigeumgayong.backend.analysis.service.ImprovementObservations.Observation;
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

/**
 * 일반 보완 제안(app.analysis.general-suggestion.enabled=true): 부족한 점이 없는 시설도 AI가 제안을 만든다.
 * 가짜 LLM만 쓰며, 근거 id 검증·캐시·한도 흐름은 기존과 같아야 한다.
 */
@SpringBootTest(properties = "app.analysis.general-suggestion.enabled=true")
@Import(FakeLlmConfig.class)
class ImprovementGeneralSuggestionTest {

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
    Facility target;
    int seq = 0;

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
    }

    /** 화요일 09:00-22:00, 다른 요일 정보 없음, 정류장 정보 없음 → 부족한 점(관찰)이 하나도 없는 시설 */
    private Facility noIssueFacility() {
        target = facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(AccessType.OPEN_HOURS).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(target.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(target.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) 1320).build());
        return target;
    }

    /** 희망 시간(19:00-21:00)에 열려 있으니 이용 가능하다 */
    private AnalysisResult run() {
        return service.analyze(String.valueOf(target.getFacilityId()), null, null, null, TUE, "19:00", "21:00");
    }

    @Test
    void 부족한_점이_없어도_LLM이_일반_보완_제안을_만든다() {
        noIssueFacility();

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.availability().status()).isEqualTo("AVAILABLE");
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(item.alternatives()).isNotEmpty();
        assertThat(item.integratedSuggestion().text()).isNotBlank();
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void LLM_입력에_일반_보완_검토_관찰_하나만_들어간다() {
        noIssueFacility();
        List<String> inputs = new ArrayList<>();
        llm.responder = input -> {
            inputs.add(input);
            return FakeLlmClient.validResponse(input);
        };

        run();

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("\"id\":\"O1\"").contains("GENERAL_REVIEW")
                .contains("새로운 문제나 수치를 만들지 말 것");
        assertThat(inputs.get(0).split("\"type\":\"")).hasSize(2); // 관찰이 하나뿐이다
    }

    @Test
    void 두_번째_요청은_캐시_HIT이라_LLM을_다시_호출하지_않는다() {
        noIssueFacility();

        run();
        AnalysisResult second = run();

        assertThat(second.data().items().get(0).cache().status()).isEqualTo("HIT");
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void 없는_근거_id를_쓰면_검증에서_탈락해_저장하지_않는다() {
        noIssueFacility();
        llm.responder = input -> FakeLlmClient.validResponse(input).replace("\"O1\"", "\"O9\"");

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.proposalStatus()).isEqualTo("UNAVAILABLE");
        assertThat(item.cache().status()).isEqualTo("UNAVAILABLE");
        assertThat(aiRepository.count()).isZero();
    }

    @Test
    void 일반_보완_관찰_문구에는_숫자가_없어_숫자_검증과_충돌하지_않는다() {
        Observation o = ImprovementObservations.generalReview();

        assertThat(o.id()).isEqualTo("O1");
        assertThat(o.type()).isEqualTo(ImprovementObservations.GENERAL_REVIEW);
        assertThat(o.detail()).doesNotContainPattern("\\d");
    }
}
