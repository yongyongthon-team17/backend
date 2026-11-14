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

/**
 * 경기도 유동인구 통계 비교 관찰(FLOW_*). 가짜 LLM의 입력(facts JSON)으로 관찰 내용을 확인한다.
 * 동종 시설 비교는 끄고 유동인구 쪽만 본다.
 */
@SpringBootTest(properties = "app.analysis.peer-comparison.enabled=false")
@Import(FakeLlmConfig.class)
class ImprovementFlowStatisticsTest {

    static final int TUE = 1;
    static final int SAT = 5;

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

    private Facility facility(String district) {
        Facility f = facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district(district).accessType(AccessType.OPEN_HOURS).name("시설" + seq)
                .address("용인시 " + district).latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1"))
                .build());
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        return f;
    }

    private void open(Facility f, int day, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) day).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    private ImprovementItem run(Facility f, int day, String start, String end) {
        return service.analyze(String.valueOf(f.getFacilityId()), null, null, null, day, start, end).data().items()
                .get(0);
    }

    @Test
    void 권장_마감보다_일찍_닫으면_구_이름과_권장_시각이_관찰에_들어간다() {
        Facility f = facility("처인구");
        open(f, TUE, 540, 1080); // 18:00에 마감

        run(f, TUE, "10:00", "12:00"); // 희망 시간엔 운영 중이지만 마감이 이르다

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("FLOW_CLOSING").contains("경기도 처인구 유동인구 통계")
                .contains("22:00까지 유동 인구가 많은 시간대").contains("18:00에 마감");
    }

    @Test
    void 연도와_수치는_LLM_입력에_넣지_않는다() {
        Facility f = facility("수지구");
        open(f, TUE, 540, 1080);

        run(f, TUE, "10:00", "12:00");

        String facts = inputs.get(0);
        assertThat(facts).doesNotContain("2024").doesNotContain("2023").doesNotContain("%");
        assertThat(facts).doesNotContain("3049005"); // seed의 유동인구 원값
    }

    @Test
    void 권장_마감_이후까지_운영하면_마감_관찰이_없다() {
        Facility f = facility("기흥구");
        open(f, TUE, 540, 1320); // 22:00까지

        ImprovementItem item = run(f, TUE, "10:00", "12:00");

        assertThat(inputs).isEmpty(); // 부족한 점이 없어 LLM을 부르지 않는다
        assertThat(item.proposalReason()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
    }

    @Test
    void 같은_구_같은_유형_시설_대부분이_닫은_붐비는_시간에_공백_관찰이_생긴다() {
        for (int i = 0; i < 6; i++) { // 같은 구·같은 유형 6곳이 모두 18시에 닫음
            open(facility("수지구"), TUE, 540, 1080);
        }
        Facility target = facility("수지구");
        open(target, TUE, 540, 1080);

        run(target, TUE, "19:00", "21:00"); // 수지구 저녁 19~21시는 붐비는 시간대

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("FLOW_SUPPLY_GAP").contains("경기도 수지구 유동인구 통계")
                .contains("19:00~21:00").contains("절반에 못 미치고");
    }

    @Test
    void 이미_이_시설이_그_시간에_운영하면_공백_관찰이_없다() {
        for (int i = 0; i < 6; i++) {
            open(facility("수지구"), TUE, 540, 1080);
        }
        Facility target = facility("수지구");
        open(target, TUE, 540, 1320); // 22:00까지 운영

        run(target, TUE, "19:00", "21:00");

        assertThat(inputs).isEmpty();
    }

    @Test
    void 주말_유동_인구가_평일_수준이고_이_시설이_휴관이면_주말_관찰이_생긴다() {
        Facility f = facility("수지구"); // 수지구 토요일은 평일 평균의 약 97%
        for (int d = 0; d < 5; d++) {
            open(f, d, 540, 1320); // 평일 22:00까지, 토요일은 휴관(운영시간 행 없음 → 모름)
        }
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) SAT).intervalSeq((short) 1).dayState(DayState.CLOSED).build());

        run(f, SAT, "10:00", "12:00");

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("FLOW_WEEKEND").contains("경기도 수지구 유동인구 통계")
                .contains("평일 못지않은 유동 인구").contains("운영하지 않음");
    }
}
