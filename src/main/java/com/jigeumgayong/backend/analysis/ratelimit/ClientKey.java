package com.jigeumgayong.backend.analysis.ratelimit;

/**
 * 한도를 셀 때 쓰는 요청자 식별 정보. 로그인이 없어 UUID(프론트가 보낸 익명 ID)와 IP만 있다.
 * UUID는 사용자가 바꿀 수 있어 "개인 구분"용이고, 우회 방지는 IP·전체 한도가 맡는다.
 * IP는 개인정보라 메모리에서만 쓰고 로그에는 원문을 남기지 않는다(toString은 가려서 출력).
 *
 * @param clientId 형식이 검증된 소문자 UUID. 없으면 null
 * @param ip       정규화된 IP 문자열(IPv6는 /64 앞부분)
 */
public record ClientKey(String clientId, String ip) {

    /** 식별 정보가 없는 내부 호출·테스트용 */
    public static final ClientKey ANONYMOUS = new ClientKey(null, "unknown");

    /** 개인 한도의 키. UUID가 없으면 IP를 개인으로 본다(헤더를 빼서 한도를 늘리지 못하게). */
    String personalKey() {
        return clientId != null ? "c:" + clientId : "i:" + ip;
    }

    @Override
    public String toString() {
        String id = clientId == null ? "-" : clientId.substring(0, 4) + "…";
        return "ClientKey[id=" + id + ", ip=***]";
    }
}
