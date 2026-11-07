package com.jigeumgayong.backend.analysis.llm;

/** LLM 호출 실패·타임아웃·거부·키 없음. A04는 이 예외를 200 부분 응답(LLM_UNAVAILABLE)으로 바꾼다. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
