package com.jigeumgayong.backend.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchOptionsResponse;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.service.FacilitySearchOptionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;

class FacilitySearchOptionServiceTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    FacilityTypeRepository repository = mock(FacilityTypeRepository.class);

    private FacilitySearchOptionService serviceAt(String instant) {
        return new FacilitySearchOptionService(repository, Clock.fixed(Instant.parse(instant), SEOUL));
    }

    @Test
    void monday_is_0() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of());
        // 2026-11-09 월요일 정오(KST)
        assertThat(serviceAt("2026-11-09T03:00:00Z").getOptions().defaultDayOfWeek()).isEqualTo(0);
    }

    @Test
    void sunday_is_6() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of());
        // 2026-11-15 일요일 정오(KST)
        assertThat(serviceAt("2026-11-15T03:00:00Z").getOptions().defaultDayOfWeek()).isEqualTo(6);
    }

    @Test
    void uses_seoul_date_not_utc_at_midnight_boundary() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of());
        // UTC로는 일요일 15:30이지만 KST로는 월요일 00:30 → 0
        assertThat(serviceAt("2026-11-15T15:30:00Z").getOptions().defaultDayOfWeek()).isEqualTo(0);
    }

    @Test
    void maps_types_sorted_by_type_id_and_passes_image_through() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of(
                type("도서관", null),
                type("생활체육시설", "/img/sports.png")));

        FacilitySearchOptionsResponse r = serviceAt("2026-11-09T03:00:00Z").getOptions();

        ArgumentCaptor<Sort> sort = ArgumentCaptor.forClass(Sort.class);
        verify(repository).findAll(sort.capture());
        assertThat(sort.getValue()).isEqualTo(Sort.by("typeId"));
        assertThat(r.facilityTypes()).extracting("name").containsExactly("도서관", "생활체육시설");
        assertThat(r.facilityTypes().get(0).defaultImageUrl()).isNull();
        assertThat(r.facilityTypes().get(1).defaultImageUrl()).isEqualTo("/img/sports.png");
    }

    @Test
    void fixed_codes() {
        when(repository.findAll(any(Sort.class))).thenReturn(List.of());

        FacilitySearchOptionsResponse r = serviceAt("2026-11-09T03:00:00Z").getOptions();

        assertThat(r.facilityTypes()).isEmpty();
        assertThat(r.districts()).containsExactly("수지구", "기흥구", "처인구");
        assertThat(r.daysOfWeek()).hasSize(7);
        assertThat(r.daysOfWeek().get(0).label()).isEqualTo("월");
        assertThat(r.daysOfWeek().get(6).value()).isEqualTo(6);
        assertThat(r.daysOfWeek().get(6).label()).isEqualTo("일");
        assertThat(r.timeZone()).isEqualTo("Asia/Seoul");
    }

    private static short nextId = 1;

    private static FacilityType type(String name, String image) {
        FacilityType t = FacilityType.builder().name(name).chipGroup(validChipGroup()).defaultImage(image).build();
        // IDENTITY 생성값 대신 테스트용 id를 직접 지정
        org.springframework.test.util.ReflectionTestUtils.setField(t, "typeId", nextId++);
        return t;
    }

    private static String validChipGroup() {
        return com.jigeumgayong.backend.facility.domain.ChipGroup.ALL.get(0);
    }
}
