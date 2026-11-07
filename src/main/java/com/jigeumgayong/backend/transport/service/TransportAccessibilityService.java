package com.jigeumgayong.backend.transport.service;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A05 시설 교통 접근성. 적재된 facility.bus_stop_count만 읽는다(조회 중 외부 API 호출 없음).
 * 등급은 산식(R11) 합의 전이라 항상 null이다. 정류장이 있다고 '양호'로 판정하지 않는다.
 */
@Service
public class TransportAccessibilityService {

    /** 06 A05: 반경은 500m 고정 (사용자 변경 파라미터 없음) */
    static final int RADIUS_METERS = 500;

    static final String NOTE_NO_DATA = "500m 정류장 집계값이 적재되지 않았습니다.";
    static final String NOTE_GRADE_UNDEFINED = "등급 기준은 추가 논의가 필요합니다.";
    static final String NOTE_NOT_TIME_BASED = "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다.";

    // A03과 같은 규칙: 양수 10진수 문자열
    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9][0-9]*$");

    private final FacilityRepository facilityRepository;

    // 기능 on/off 스위치(503 FEATURE_NOT_ENABLED)는 쓰지 않는다. CSV를 미리 적재해 DB 값만 읽는 방식이라
    // 외부 API 비활성 상황이 없고, 자료가 없으면 NO_DATA로 응답한다. 필요해지면 아래 주석을 되살린다.
    // private final boolean enabled;
    //
    // public TransportAccessibilityService(FacilityRepository facilityRepository,
    //                                      @Value("${transport.enabled:true}") boolean enabled) {
    //     this.facilityRepository = facilityRepository;
    //     this.enabled = enabled;
    // }
    // ... get() 맨 앞: if (!enabled) { throw new BusinessException(ErrorCode.FEATURE_NOT_ENABLED); }

    public TransportAccessibilityService(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    @Transactional(readOnly = true)
    public TransportAccessibilityResponse get(String facilityIdText) {
        long facilityId = parseFacilityId(facilityIdText);
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        Integer count = f.getBusStopCount();
        Accessibility accessibility = count == null
                // 미확인은 0으로 바꾸지 않는다
                ? new Accessibility(Status.NO_DATA, RADIUS_METERS, null, null, List.of(NOTE_NO_DATA))
                : new Accessibility(Status.AVAILABLE, RADIUS_METERS, count, null,
                        List.of(NOTE_GRADE_UNDEFINED, NOTE_NOT_TIME_BASED));
        return new TransportAccessibilityResponse(String.valueOf(f.getFacilityId()), accessibility);
    }

    private static long parseFacilityId(String text) {
        if (text == null || !POSITIVE_INT.matcher(text).matches()) {
            throw invalidRequest(ErrorDetail.INVALID_FORMAT);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalidRequest(ErrorDetail.OUT_OF_RANGE);
        }
    }

    private static BusinessException invalidRequest(String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail("facilityId", reason)));
    }
}
