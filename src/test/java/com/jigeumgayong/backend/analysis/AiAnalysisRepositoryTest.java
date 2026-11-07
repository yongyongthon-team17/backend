package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.domain.AnalysisType;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.support.H2PostgresJpaTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@H2PostgresJpaTest
class AiAnalysisRepositoryTest {

    private static final String HASH = "a".repeat(64);
    private static final String JSON = "{\"score\":82,\"grade\":\"B\",\"items\":[\"주차\",\"버스\"],\"nested\":{\"k\":\"한글\"}}";

    @Autowired AiAnalysisRepository repository;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private AiAnalysis sample(String scopeKey, AnalysisType type, String params) {
        return AiAnalysis.builder()
                .scopeKey(scopeKey).analysisType(type).paramsKey(params)
                .payload(JSON).sourceHash(HASH).promptVersion("v1").modelName("gpt-6-luna")
                .build();
    }

    @Test
    void payload_JSON이_문자열로_왕복한다() {
        AiAnalysis saved = repository.saveAndFlush(sample("ALL", AnalysisType.REGION_INSIGHT, ""));
        em.clear();

        AiAnalysis found = repository.findById(saved.getAnalysisId()).orElseThrow();

        // 그대로 돌아오는지(따옴표로 한 번 더 감싸지지 않는지) 확인
        assertThat(found.getPayload()).doesNotStartWith("\"");
        assertThat(found.getPayload()).contains("\"score\"").contains("한글");

        // DB에 JSON 객체로 저장됐는지: JSON 문자열 리터럴이면 '"{...}"' 형태가 된다
        String raw = jdbc.queryForObject("select cast(payload as varchar) from ai_analysis", String.class);
        assertThat(raw).startsWith("{");
    }

    @Test
    void enum은_문자열로_저장되고_기본값_왕복() {
        AiAnalysis saved = repository.saveAndFlush(AiAnalysis.builder()
                .scopeKey("FAC:12").analysisType(AnalysisType.IMPROVEMENT)
                .paramsKey("S:GENERAL|D:1|T:1140-1260").facilityId(12L).spaceType(SpaceType.GENERAL)
                .payload(JSON).sourceHash(HASH).promptVersion("v1").build());
        em.clear();

        assertThat(jdbc.queryForObject("select analysis_type from ai_analysis", String.class)).isEqualTo("IMPROVEMENT");
        assertThat(jdbc.queryForObject("select space_type from ai_analysis", String.class)).isEqualTo("GENERAL");

        AiAnalysis found = repository.findById(saved.getAnalysisId()).orElseThrow();
        assertThat(found.getSpaceType()).isEqualTo(SpaceType.GENERAL);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getSourceHash()).hasSize(64);
    }

    @Test
    void paramsKey를_생략하면_빈_문자열이다() {
        AiAnalysis saved = repository.saveAndFlush(sample("ALL", AnalysisType.REGION_INSIGHT, null));
        assertThat(saved.getParamsKey()).isEmpty();
    }

    @Test
    void 캐시_키로_조회한다() {
        repository.saveAndFlush(sample("DIST:수지구", AnalysisType.REGION_INSIGHT, ""));

        assertThat(repository.findByScopeKeyAndAnalysisTypeAndParamsKey(
                "DIST:수지구", AnalysisType.REGION_INSIGHT, "")).isPresent();
        assertThat(repository.findByScopeKeyAndAnalysisTypeAndParamsKey(
                "DIST:기흥구", AnalysisType.REGION_INSIGHT, "")).isEmpty();
    }

    @Test
    void 같은_캐시_키는_UNIQUE_위반이다() {
        repository.saveAndFlush(sample("ALL", AnalysisType.REGION_INSIGHT, ""));

        assertThatThrownBy(() -> repository.saveAndFlush(sample("ALL", AnalysisType.REGION_INSIGHT, "")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 재생성하면_payload와_updatedAt이_갱신된다() throws Exception {
        AiAnalysis saved = repository.saveAndFlush(sample("ALL", AnalysisType.REGION_INSIGHT, ""));
        var createdAt = saved.getCreatedAt();
        var firstUpdatedAt = saved.getUpdatedAt();
        Thread.sleep(20);

        saved.regenerate("{\"score\":1}", "b".repeat(64), "v2", "gpt-6.1-terra");
        repository.saveAndFlush(saved);
        em.clear();

        AiAnalysis found = repository.findById(saved.getAnalysisId()).orElseThrow();
        assertThat(found.getPayload()).contains("\"score\"").contains("1");
        assertThat(found.getPromptVersion()).isEqualTo("v2");
        assertThat(found.getCreatedAt()).isEqualTo(createdAt);
        assertThat(found.getUpdatedAt()).isAfter(firstUpdatedAt);
    }
}
