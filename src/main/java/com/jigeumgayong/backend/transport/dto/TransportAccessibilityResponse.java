package com.jigeumgayong.backend.transport.dto;

import java.util.List;

/** A05 응답 data (06 A05). Accessibility 는 A04의 transport 필드에서도 같은 타입으로 쓴다. */
public record TransportAccessibilityResponse(String facilityId, Accessibility accessibility) {

    /** AVAILABLE=정류장 수 있음(0 포함) / NO_DATA=미적재(null) / NOT_ENABLED=기능 비활성(A04 부분 결과용) */
    public enum Status {
        AVAILABLE, NO_DATA, NOT_ENABLED
    }

    /**
     * @param stopCount facility.bus_stop_count. 반경 500m 정류장 수. null=미확인, 0=확인 결과 0곳
     * @param grade     등급 산식(R11) 합의 전까지 항상 null
     */
    public record Accessibility(Status status, int radiusMeters, Integer stopCount, Grade grade,
                                List<String> notes) {
    }

    public record Grade(String code, String label, String ruleVersion) {
    }
}
