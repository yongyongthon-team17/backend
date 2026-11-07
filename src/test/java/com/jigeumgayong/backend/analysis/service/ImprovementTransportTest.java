package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.service.TransportAccessibilityService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ImprovementTransportTest {

    @Test
    void null은_NO_DATA이고_0은_AVAILABLE이다() {
        Accessibility none = ImprovementTransport.fromCount(null);
        assertThat(none.status().name()).isEqualTo("NO_DATA");
        assertThat(none.stopCount()).isNull();

        Accessibility zero = ImprovementTransport.fromCount(0);
        assertThat(zero.status().name()).isEqualTo("AVAILABLE");
        assertThat(zero.stopCount()).isZero();
        assertThat(zero.grade()).isNull();
    }

    /** A05 서비스 출력과 값·notes가 같아야 한다 (문구가 한쪽만 바뀌면 여기서 깨진다) */
    @Test
    void A05_출력과_동일하다() {
        for (Integer count : new Integer[] { null, 0, 4 }) {
            Facility f = Facility.builder().district("수지구").busStopCount(count).build();
            FacilityRepository repo = mock(FacilityRepository.class);
            Mockito.when(repo.findById(1L)).thenReturn(Optional.of(f));

            Accessibility a05 = new TransportAccessibilityService(repo).get("1").accessibility();

            assertThat(ImprovementTransport.fromCount(count)).as("count=%s", count).isEqualTo(a05);
        }
    }

    @Test
    void 스위치가_꺼져_있으면_NOT_ENABLED다() {
        Accessibility a = ImprovementTransport.of(candidateWith(4), false);

        assertThat(a.status().name()).isEqualTo("NOT_ENABLED");
        assertThat(a.stopCount()).isNull();
    }

    @Test
    void 변환_중_예외가_나면_NOT_ENABLED로_내려간다() {
        Candidate broken = new Candidate(null, null, "x", null, null, false, 1080, true, 1, 1140, 1260);

        assertThat(ImprovementTransport.of(broken, true).status().name()).isEqualTo("NOT_ENABLED");
    }

    @Test
    void 켜져_있으면_Facility_값으로_채운다() {
        assertThat(ImprovementTransport.of(candidateWith(4), true).stopCount()).isEqualTo(4);
        assertThat(ImprovementTransport.of(candidateWith(null), true).status().name()).isEqualTo("NO_DATA");
    }

    private static Candidate candidateWith(Integer count) {
        Facility f = Facility.builder().district("수지구").busStopCount(count).build();
        return new Candidate(f, null, "x", null, null, false, 1080, true, 1, 1140, 1260);
    }
}
