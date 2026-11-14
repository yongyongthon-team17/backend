package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 서버 고정 문구에서 공간 이름 뒤에 붙이는 주제 조사(은/는). */
class TopicParticleTest {

    @Test
    void 받침이_있으면_은_없으면_는을_붙인다() {
        assertThat(ImprovementAnalysisService.withTopicParticle("종합자료실")).isEqualTo("종합자료실은"); // 실: ㄹ 받침
        assertThat(ImprovementAnalysisService.withTopicParticle("열람실")).isEqualTo("열람실은");
        assertThat(ImprovementAnalysisService.withTopicParticle("시설 전체")).isEqualTo("시설 전체는"); // 체: 받침 없음
        assertThat(ImprovementAnalysisService.withTopicParticle("어린이자료관")).isEqualTo("어린이자료관은");
        assertThat(ImprovementAnalysisService.withTopicParticle("멀티미디어실")).isEqualTo("멀티미디어실은");
        assertThat(ImprovementAnalysisService.withTopicParticle("체육관")).isEqualTo("체육관은");
        assertThat(ImprovementAnalysisService.withTopicParticle("수영장")).isEqualTo("수영장은");
        assertThat(ImprovementAnalysisService.withTopicParticle("공부방")).isEqualTo("공부방은");
        assertThat(ImprovementAnalysisService.withTopicParticle("자료실2")).isEqualTo("자료실2은(는)");
    }

    @Test
    void 이름이_비어_있으면_빈_문자열이다() {
        assertThat(ImprovementAnalysisService.withTopicParticle("")).isEmpty();
        assertThat(ImprovementAnalysisService.withTopicParticle(null)).isEmpty();
    }
}
