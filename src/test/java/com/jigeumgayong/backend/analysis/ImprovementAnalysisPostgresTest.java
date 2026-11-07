package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.domain.AnalysisType;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 PostgreSQL(db/schema-postgres.sql 적용 상태)에서 JSONB·UNIQUE·재생성을 확인한다.
 * PG_TEST_URL 환경 변수가 있을 때만 실행된다(없으면 skip). 예:
 * PG_TEST_URL=jdbc:postgresql://localhost:55432/jgg PG_TEST_USER=jgg PG_TEST_PASSWORD=...
 */
@SpringBootTest
@Import(FakeLlmConfig.class)
@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
class ImprovementAnalysisPostgresTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PG_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("PG_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("PG_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none"); // 스키마는 schema-postgres.sql이 담당
    }

    @Autowired ImprovementAnalysisService service;
    @Autowired FakeLlmClient llm;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    Facility facility;

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
        facility = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("pg-1").district("수지구").accessType(AccessType.OPEN_HOURS).name("시설").address("주소")
                .latitude(new BigDecimal("37.32")).longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(facility.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        setClose(1080);
    }

    private void setClose(int closeMin) {
        hourRepository.deleteAll();
        hourRepository.save(OperatingHour.builder().facilityId(facility.getFacilityId()).spaceType(SpaceType.GENERAL)
                .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) closeMin).build());
    }

    private AnalysisResult run() {
        return service.analyze(null, null, null, 1, "19:00", "21:00");
    }

    @Test
    void JSONB로_저장되고_조회되며_HIT으로_재사용된다() {
        ImprovementItem created = run().data().items().get(0);
        ImprovementItem hit = run().data().items().get(0);

        assertThat(created.cache().status()).isEqualTo("CREATED");
        assertThat(hit.cache().status()).isEqualTo("HIT");
        assertThat(hit.suggestionId()).isEqualTo(created.suggestionId());
        assertThat(hit.alternatives()).isEqualTo(created.alternatives());
        assertThat(llm.calls.get()).isEqualTo(1);

        // 문자열 리터럴이 아니라 JSON 객체로 저장됐는지 (CHECK jsonb_typeof(payload)='object'도 통과)
        assertThat(jdbc.queryForObject("select jsonb_typeof(payload) from ai_analysis", String.class))
                .isEqualTo("object");
        assertThat(jdbc.queryForObject("select payload->>'proposedCloseMin' from ai_analysis", String.class))
                .isEqualTo("1260");
        assertThat(jdbc.queryForObject("select jsonb_typeof(payload->'alternatives') from ai_analysis", String.class))
                .isEqualTo("array");
        assertThat(jdbc.queryForObject("select space_type from ai_analysis", String.class)).isEqualTo("GENERAL");
        assertThat(jdbc.queryForObject("select length(source_hash) from ai_analysis", Integer.class)).isEqualTo(64);
    }

    @Test
    void 같은_키를_다시_넣으면_UNIQUE_충돌이고_재조회로_저장된_행을_읽는다() {
        run();
        AiAnalysis existing = aiRepository.findAll().get(0);

        assertThatThrownBy(() -> aiRepository.saveAndFlush(AiAnalysis.builder()
                .scopeKey(existing.getScopeKey()).analysisType(AnalysisType.IMPROVEMENT)
                .paramsKey(existing.getParamsKey()).facilityId(existing.getFacilityId())
                .spaceType(existing.getSpaceType()).payload(existing.getPayload())
                .sourceHash(existing.getSourceHash()).promptVersion("imp-v2").build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        AiAnalysis found = aiRepository.findByScopeKeyAndAnalysisTypeAndParamsKey(
                existing.getScopeKey(), AnalysisType.IMPROVEMENT, existing.getParamsKey()).orElseThrow();
        assertThat(found.getAnalysisId()).isEqualTo(existing.getAnalysisId());
        assertThat(aiRepository.count()).isEqualTo(1);
    }

    @Test
    void 동시_동일_요청은_LLM_한_번에_행_하나로_수렴한다() throws Exception {
        llm.delayMillis = 300;
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            var f1 = pool.submit(() -> { go.await(); return run(); });
            var f2 = pool.submit(() -> { go.await(); return run(); });
            go.countDown();
            assertThat(List.of(f1.get().data().items().get(0).cache().status(),
                    f2.get().data().items().get(0).cache().status()))
                    .containsExactlyInAnyOrder("CREATED", "HIT");
            assertThat(llm.calls.get()).isEqualTo(1);
            assertThat(aiRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void source_hash가_달라지면_같은_행을_재생성한다() {
        ImprovementItem first = run().data().items().get(0);
        String oldHash = jdbc.queryForObject("select source_hash from ai_analysis", String.class);

        setClose(1020); // 18:00 → 17:00 마감으로 운영시간 변경
        AnalysisResult r = run();
        ImprovementItem second = r.data().items().get(0);

        assertThat(second.cache().status()).isEqualTo("CREATED");
        assertThat(second.suggestionId()).isEqualTo(first.suggestionId()); // 같은 행 UPDATE
        assertThat(r.warnings()).anyMatch(w -> w.code().equals("CACHE_INVALIDATED"));
        assertThat(aiRepository.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source_hash from ai_analysis", String.class)).isNotEqualTo(oldHash);
        assertThat(jdbc.queryForObject("select updated_at >= created_at from ai_analysis", Boolean.class)).isTrue();
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    @Test
    void 존재하지_않는_공간의_결과는_복합_FK로_거부된다() {
        assertThatThrownBy(() -> aiRepository.saveAndFlush(AiAnalysis.builder()
                .scopeKey("FAC:" + facility.getFacilityId()).analysisType(AnalysisType.IMPROVEMENT)
                .paramsKey("S:READING|D:1|T:1140-1260").facilityId(facility.getFacilityId())
                .spaceType(SpaceType.READING) // 이 시설엔 READING 공간이 없다
                .payload("{\"a\":1}").sourceHash("a".repeat(64)).promptVersion("imp-v2").build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
