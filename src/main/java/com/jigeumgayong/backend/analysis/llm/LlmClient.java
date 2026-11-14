package com.jigeumgayong.backend.analysis.llm;

import java.util.Map;

/** LLM 호출을 감싸는 인터페이스. 테스트에서는 가짜 구현으로 바꾼다. */
public interface LlmClient {

    /**
     * JSON 스키마를 따르는 응답 본문(JSON 문자열)을 돌려준다.
     *
     * @throws LlmUnavailableException 호출 실패, 타임아웃, 거부, 미완료 응답, 키 없음
     */
    String generateJson(String instructions, String input, String schemaName, Map<String, Object> schema);

    /** ai_analysis.model_name에 저장할 모델명 */
    String modelName();

    /** 요청에 넣는 추론 수준(reasoning.effort). 모델명과 함께 캐시 source_hash에 들어간다 */
    String reasoningEffort();
}
