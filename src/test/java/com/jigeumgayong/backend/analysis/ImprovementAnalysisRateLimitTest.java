package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.ratelimit.ClientKey;
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
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.support.FakeLlmClient;
import com.jigeumgayong.backend.support.FakeLlmConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * AI 호출 한도가 A04 흐름에 올바르게 연결됐는지 확인한다. 가짜 LLM만 쓰므로 실제 OpenAI는 호출하지 않는다.
 * 한도를 개인 2건으로 낮춰 켠다(기본 테스트 설정은 꺼져 있다).
 */
@SpringBootTest(properties = {
        "app.llm.rate-limit.enabled=true",
        "app.llm.rate-limit.per-client=2",
        "app.llm.rate-limit.per-ip=100",
        "app.llm.rate-limit.global=100"
})
@Import(FakeLlmConfig.class)
class ImprovementAnalysisRateLimitTest {

    static final int TUE = 1;
    static final AtomicInteger IP_SEQ = new AtomicInteger();

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

    /** 컨텍스트가 테스트 사이에 한도 기록을 공유하므로, 테스트마다 새 사람(UUID+IP)을 쓴다. */
    private static ClientKey newPerson() {
        return new ClientKey(UUID.randomUUID().toString(), "10.0.0." + IP_SEQ.incrementAndGet());
    }

    /** 마지막으로 만든 시설. run()이 이 시설의 facilityId로 요청한다(A04는 facilityId 필수) */
    Facility target;

    /** 화요일 09:00-closeMin 운영하는 공간들을 가진 시설 */
    private Facility facility(int closeMin, SpaceType... spaces) {
        target = facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(AccessType.OPEN_HOURS).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
        for (SpaceType t : spaces) {
            spaceRepository.save(new FacilitySpace(target.getFacilityId(), t, t.name()));
            hourRepository.save(OperatingHour.builder().facilityId(target.getFacilityId()).spaceType(t)
                    .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(DayState.OPEN)
                    .openMin((short) 540).closeMin((short) closeMin).build());
        }
        return target;
    }

    /** 화요일 09:00-18:00 운영(저녁에 닫힘) 시설 */
    private Facility closedEvening() {
        return facility(1080, SpaceType.DEFAULT);
    }

    private AnalysisResult run(ClientKey who, String start, String end) {
        return service.analyze(String.valueOf(target.getFacilityId()), null, null, null, TUE, start, end, who);
    }

    private static List<String> codes(AnalysisResult r) {
        return r.warnings().stream().map(Warning::code).toList();
    }

    @Test
    void 한도_안에서는_모두_생성하고_초과한_항목만_RATE_LIMITED로_응답한다() {
        facility(1080, SpaceType.values()); // 한 시설의 공간 3개가 모두 후보, 한도 2건
        ClientKey me = newPerson();

        AnalysisResult r = run(me, "19:00", "21:00");

        List<ImprovementItem> items = r.data().items();
        assertThat(items).hasSize(3);
        assertThat(items.stream().filter(i -> "GENERATED".equals(i.proposalStatus()))).hasSize(2);
        List<ImprovementItem> limited = items.stream().filter(i -> "UNAVAILABLE".equals(i.proposalStatus())).toList();
        assertThat(limited).hasSize(1);
        assertThat(limited.get(0).proposalReason()).contains("AI 호출 한도를 초과했습니다").contains("초 후 다시 시도");
        assertThat(limited.get(0).cache().status()).isEqualTo("UNAVAILABLE");
        assertThat(codes(r)).contains("RATE_LIMITED");
        assertThat(llm.calls.get()).isEqualTo(2); // 한도에 걸린 항목은 OpenAI를 호출하지 않는다
        assertThat(r.retryAfterSeconds()).isNotNull().isBetween(1L, 180L);
    }

    @Test
    void 캐시_HIT은_한도를_소모하지_않는다() {
        closedEvening();
        ClientKey me = newPerson();

        run(me, "19:00", "21:00"); // CREATED: 1건 소모
        for (int i = 0; i < 5; i++) {
            AnalysisResult hit = run(me, "19:00", "21:00");
            assertThat(hit.data().items().get(0).cache().status()).isEqualTo("HIT");
            assertThat(codes(hit)).doesNotContain("RATE_LIMITED");
            assertThat(hit.retryAfterSeconds()).isNull();
        }
        assertThat(llm.calls.get()).isEqualTo(1);

        // HIT 5번이 한도를 쓰지 않았으므로 새 조건 1건이 더 가능하고, 그다음에 막힌다
        assertThat(run(me, "20:00", "22:00").data().items().get(0).proposalStatus()).isEqualTo("GENERATED");
        AnalysisResult blocked = run(me, "21:00", "23:00");
        assertThat(blocked.data().items().get(0).proposalStatus()).isEqualTo("UNAVAILABLE");
        assertThat(codes(blocked)).contains("RATE_LIMITED");
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    @Test
    void LLM_호출이_실패해도_한도는_소모된다() {
        closedEvening();
        llm.fail = true;
        ClientKey me = newPerson();

        run(me, "19:00", "21:00"); // 실패(캐시에 저장되지 않음), 1건 소모
        run(me, "19:00", "21:00"); // 다시 호출, 2건 소모
        AnalysisResult third = run(me, "19:00", "21:00");

        assertThat(llm.calls.get()).isEqualTo(2);
        assertThat(codes(third)).contains("RATE_LIMITED");
        assertThat(third.data().items().get(0).proposalReason()).contains("AI 호출 한도를 초과했습니다");
    }

    @Test
    void 한도에_걸린_사람이_있어도_다른_사람은_정상_이용한다() {
        closedEvening();
        ClientKey heavy = newPerson();
        run(heavy, "19:00", "21:00");
        run(heavy, "20:00", "22:00");
        assertThat(codes(run(heavy, "21:00", "23:00"))).contains("RATE_LIMITED");

        AnalysisResult other = run(newPerson(), "18:30", "20:30");

        assertThat(other.data().items().get(0).proposalStatus()).isEqualTo("GENERATED");
        assertThat(codes(other)).doesNotContain("RATE_LIMITED");
    }

    @Test
    void 후보가_없거나_한도_미만이면_Retry_After_정보가_없다() {
        facility(1320, SpaceType.DEFAULT); // 22시까지 운영 → 19-21시 후보 없음

        AnalysisResult none = run(newPerson(), "19:00", "21:00");

        assertThat(none.data().items()).isEmpty();
        assertThat(none.retryAfterSeconds()).isNull();
    }
}
