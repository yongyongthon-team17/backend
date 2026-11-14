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
 * 같은 행정구·같은 유형 시설과의 운영시간 비교 관찰(PEER_*). 가짜 LLM의 입력(facts JSON)으로 관찰 내용을 확인한다.
 */
@SpringBootTest(properties = "app.analysis.flow-statistics.enabled=false") // 유동인구 비교는 ImprovementFlowStatisticsTest·ImprovementCombinedClosingTest에서 본다
@Import(FakeLlmConfig.class)
class ImprovementPeerComparisonTest {

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
    FacilityType sports;
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
        sports = typeRepository.save(FacilityType.builder().name("생활체육시설").chipGroup(ChipGroup.SPORTS).build());
        inputs = new ArrayList<>();
        llm.responder = input -> {
            inputs.add(input);
            return FakeLlmClient.validResponse(input);
        };
    }

    /** 화요일에 openMin~closeMin 운영하는 DEFAULT 공간 하나짜리 시설 */
    private Facility facility(FacilityType type, String district, int openMin, int closeMin) {
        Facility f = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district(district).accessType(AccessType.OPEN_HOURS).name("시설" + seq)
                .address("용인시 " + district).latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1"))
                .build());
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
        return f;
    }

    private void peers(int count, FacilityType type, String district, int closeMin) {
        for (int i = 0; i < count; i++) {
            facility(type, district, 540, closeMin);
        }
    }

    private List<ImprovementItem> run(Facility target) {
        return service.analyze(String.valueOf(target.getFacilityId()), null, null, null, TUE, "19:00", "21:00")
                .data().items();
    }

    @Test
    void 비교_시설이_더_늦게_열면_마감과_희망_시간_관찰이_함께_들어간다() {
        peers(6, library, "수지구", 1320); // 같은 구·같은 유형 6곳이 22:00까지
        Facility target = facility(library, "수지구", 540, 1080); // 18:00에 마감, 희망 19~21시에 닫혀 있음

        run(target);

        assertThat(inputs).hasSize(1);
        String facts = inputs.get(0);
        assertThat(facts).contains("PEER_LATER_CLOSING").contains("중앙값이 22:00").contains("18:00에 마감");
        assertThat(facts).contains("PEER_OPEN_AT_REQUEST").contains("대부분").contains("19:00~21:00");
        // 시설 수·비율 같은 숫자는 입력에 넣지 않는다
        assertThat(facts).doesNotContain("6곳").doesNotContain("%");
        // 기존 관찰(REQUEST_GAP)이 먼저, 비교 관찰이 이어서 O2, O3 id를 받는다
        assertThat(facts).contains("\"id\":\"O1\"").contains("\"id\":\"O2\"").contains("\"id\":\"O3\"");
    }

    @Test
    void 부족한_점이_없던_시설도_비교_근거가_생기면_AI_제안을_받는다() {
        peers(6, library, "수지구", 1440); // 비교 시설은 24:00까지
        Facility target = facility(library, "수지구", 540, 1320); // 22:00까지, 희망 시간엔 운영 중(이용 가능)

        List<ImprovementItem> items = run(target);

        assertThat(items.get(0).availability().status()).isEqualTo("AVAILABLE");
        assertThat(items.get(0).proposalStatus()).isEqualTo("GENERATED");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).contains("PEER_LATER_CLOSING").contains("24:00").doesNotContain("PEER_OPEN_AT_REQUEST");
    }

    @Test
    void 비교_시설이_5곳_미만이면_비교하지_않는다() {
        peers(4, library, "수지구", 1320);
        Facility target = facility(library, "수지구", 540, 1080);

        run(target);

        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0)).doesNotContain("PEER_"); // REQUEST_GAP 등 기존 관찰만
    }

    @Test
    void 다른_행정구와_다른_유형_시설은_비교에서_뺀다() {
        peers(6, library, "기흥구", 1320); // 다른 구
        peers(6, sports, "수지구", 1320); // 다른 유형
        Facility target = facility(library, "수지구", 540, 1080);

        run(target);

        assertThat(inputs.get(0)).doesNotContain("PEER_");
    }

    @Test
    void 이미_비교_시설만큼_늦게_닫으면_마감_비교_관찰이_없다() {
        peers(6, library, "수지구", 1320);
        Facility target = facility(library, "수지구", 540, 1320); // 중앙값과 같은 22:00, 희망 시간에 운영 중

        List<ImprovementItem> items = run(target);

        assertThat(inputs).isEmpty(); // 부족한 점이 없어 LLM을 부르지 않고 고정 문구
        assertThat(items.get(0).proposalReason()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
    }

    @Test
    void 비교_시설의_이름과_수는_LLM_입력에_없다() {
        peers(6, library, "수지구", 1320);
        Facility target = facility(library, "수지구", 540, 1080);

        run(target);

        String facts = inputs.get(0);
        for (Facility p : facilityRepository.findAll()) {
            if (!p.getFacilityId().equals(target.getFacilityId())) {
                assertThat(facts).doesNotContain(p.getName());
            }
        }
    }
}
