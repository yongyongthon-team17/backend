package com.jigeumgayong.backend.analysis.service;

import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * A04 개선안에 붙이는 교통 접근성. A05와 같은 판정·같은 notes를 쓰되, 후보마다 A05 서비스를 부르면
 * 시설당 조회(N+1)가 되므로 이미 로드된 Facility.busStopCount를 읽어 변환만 한다.
 * (A05 문구가 바뀌면 ImprovementTransportTest의 동일성 테스트가 깨진다.)
 */
@Slf4j
final class ImprovementTransport {

    static final int RADIUS_METERS = 500;

    static final String NOTE_NO_DATA = "500m 정류장 집계값이 적재되지 않았습니다.";
    static final String NOTE_GRADE_UNDEFINED = "등급 기준은 추가 논의가 필요합니다.";
    static final String NOTE_NOT_TIME_BASED = "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다.";
    static final String NOTE_NOT_ENABLED = "교통 접근성 분석은 아직 제공되지 않습니다.";

    private ImprovementTransport() {
    }

    /** 스위치가 꺼져 있거나 변환 중 예외가 나면 NOT_ENABLED (A04 자체는 실패하지 않는다) */
    static Accessibility of(Candidate c, boolean enabled) {
        if (!enabled) {
            return notEnabled();
        }
        try {
            return fromCount(c.facility().getBusStopCount());
        } catch (RuntimeException e) {
            log.warn("A04 교통 접근성 변환 실패(NOT_ENABLED로 대체): {}", e.getClass().getSimpleName());
            return notEnabled();
        }
    }

    /** null=미확인(NO_DATA, 0으로 바꾸지 않음), 0 이상=확인 결과(AVAILABLE) */
    static Accessibility fromCount(Integer count) {
        return count == null
                ? new Accessibility(Status.NO_DATA, RADIUS_METERS, null, null, List.of(NOTE_NO_DATA))
                : new Accessibility(Status.AVAILABLE, RADIUS_METERS, count, null,
                        List.of(NOTE_GRADE_UNDEFINED, NOTE_NOT_TIME_BASED));
    }

    static Accessibility notEnabled() {
        return new Accessibility(Status.NOT_ENABLED, RADIUS_METERS, null, null, List.of(NOTE_NOT_ENABLED));
    }
}
