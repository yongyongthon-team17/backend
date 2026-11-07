package com.jigeumgayong.backend.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import com.jigeumgayong.backend.transport.service.TransportAccessibilityService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TransportAccessibilityServiceTest {

    FacilityRepository repository = mock(FacilityRepository.class);
    TransportAccessibilityService service = new TransportAccessibilityService(repository);

    private Facility facility(Integer busStopCount) {
        Facility f = Facility.builder().district("수지구").name("시설").address("주소")
                .latitude(new BigDecimal("37.32")).longitude(new BigDecimal("127.10"))
                .busStopCount(busStopCount).build();
        ReflectionTestUtils.setField(f, "facilityId", 101L);
        return f;
    }

    @Test
    void 정류장_수가_있으면_AVAILABLE이고_등급은_null() {
        when(repository.findById(101L)).thenReturn(Optional.of(facility(4)));

        TransportAccessibilityResponse r = service.get("101");

        assertThat(r.facilityId()).isEqualTo("101");
        assertThat(r.accessibility().status()).isEqualTo(Status.AVAILABLE);
        assertThat(r.accessibility().radiusMeters()).isEqualTo(500);
        assertThat(r.accessibility().stopCount()).isEqualTo(4);
        assertThat(r.accessibility().grade()).isNull();
        assertThat(r.accessibility().notes()).hasSize(2);
    }

    @Test
    void 정류장_수_0도_AVAILABLE이다() {
        when(repository.findById(101L)).thenReturn(Optional.of(facility(0)));

        TransportAccessibilityResponse r = service.get("101");

        assertThat(r.accessibility().status()).isEqualTo(Status.AVAILABLE);
        assertThat(r.accessibility().stopCount()).isZero();
    }

    @Test
    void 정류장_수가_null이면_NO_DATA이고_0으로_바꾸지_않는다() {
        when(repository.findById(101L)).thenReturn(Optional.of(facility(null)));

        TransportAccessibilityResponse r = service.get("101");

        assertThat(r.accessibility().status()).isEqualTo(Status.NO_DATA);
        assertThat(r.accessibility().stopCount()).isNull();
        assertThat(r.accessibility().grade()).isNull();
        assertThat(r.accessibility().notes()).containsExactly("500m 정류장 집계값이 적재되지 않았습니다.");
    }

    @Test
    void 시설이_없으면_FACILITY_NOT_FOUND() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("999"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void facilityId_형식이_틀리면_DB_조회_전에_INVALID_REQUEST() {
        for (String bad : new String[] {"abc", "0", "-1", "01", "1.5", " ", "99999999999999999999"}) {
            assertThatThrownBy(() -> service.get(bad))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                        assertThat(e.getDetails().get(0).field()).isEqualTo("facilityId");
                    });
        }
        verifyNoInteractions(repository);
    }

    // 기능 on/off 스위치를 쓰지 않아 FEATURE_NOT_ENABLED 테스트는 주석 처리했다 (서비스의 enabled 주석 참고)
    // @Test
    // void 기능이_꺼져_있으면_FEATURE_NOT_ENABLED() {
    //     TransportAccessibilityService disabled = new TransportAccessibilityService(repository, false);
    //
    //     assertThatThrownBy(() -> disabled.get("101"))
    //             .isInstanceOfSatisfying(BusinessException.class,
    //                     e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FEATURE_NOT_ENABLED));
    //     verifyNoInteractions(repository);
    // }
}
