package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.SeedData.Quarantined;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 원천 CSV(src/main/resources/seed)를 변환한 결과가 Python 시드(etl_core.py, build_seed.py)의
 * 기대값과 같은지 확인한다. DB는 쓰지 않는다. CSV를 갱신하면 기대값도 함께 확인할 것.
 */
class SeedBuilderTest {

    static SeedData data;

    @BeforeAll
    static void build() {
        data = new SeedBuilder().build();
    }

    @Test
    void 원천별_시설_수가_기대값과_같다() {
        Map<SourceType, Long> bySource = data.facilities().stream()
                .collect(Collectors.groupingBy(SourceFacility::getSourceType, Collectors.counting()));
        assertThat(bySource).containsEntry(SourceType.ADMIN_CENTER, 39L)
                .containsEntry(SourceType.LIB_SMALL, 180L)
                .containsEntry(SourceType.LIB_STD, 25L)
                .containsEntry(SourceType.MANUAL, 7L)
                .containsEntry(SourceType.PUBLIC_OPEN, 97L);
        assertThat(data.facilities()).hasSize(348);
    }

    @Test
    void 좌표가_없거나_범위_밖인_작은도서관_2건만_격리된다() {
        assertThat(data.quarantine()).extracting(Quarantined::sourceType)
                .containsOnly(SourceType.LIB_SMALL).hasSize(2);
        assertThat(data.quarantine()).extracting(Quarantined::reason)
                .anyMatch(r -> r.startsWith("좌표 없음"))
                .anyMatch(r -> r.startsWith("좌표 범위 밖"));
    }

    @Test
    void 모든_시설은_공간이_1개_이상이고_좌표와_행정구가_있다() {
        assertThat(data.facilities()).allSatisfy(f -> {
            assertThat(f.getSpaces()).isNotEmpty();
            assertThat(f.getLat()).isBetween(37.0, 37.5);
            assertThat(f.getLng()).isBetween(126.9, 127.5);
            assertThat(f.getDistrict()).isIn("수지구", "기흥구", "처인구");
        });
    }

    @Test
    void GENERAL_READING_공간은_공공도서관에만_있다() {
        assertThat(data.facilities()).filteredOn(f -> f.getSpaces().containsKey(SpaceType.GENERAL))
                .allMatch(f -> f.getSourceType() == SourceType.LIB_STD);
        assertThat(data.facilities()).filteredOn(f -> f.getSpaces().containsKey(SpaceType.READING))
                .allMatch(f -> f.getSourceType() == SourceType.LIB_STD);
    }

    @Test
    void 운영시간_행_수가_Python_시드와_같다() {
        int rows = data.facilities().stream()
                .flatMap(f -> f.getSpaces().values().stream())
                .filter(sp -> sp.hours() != null)
                .mapToInt(sp -> sp.hours().values().stream().mapToInt(java.util.List::size).sum())
                .sum();
        assertThat(rows).isEqualTo(2514); // seed_core 2192 + seed_extra 322
    }

    @Test
    void 편의시설_태그_수가_기대값과_같다() {
        Map<AmenityCode, Long> byCode = data.amenities().stream()
                .collect(Collectors.groupingBy(a -> a.code(), Collectors.counting()));
        // 같은 시설에 겹쳐 붙는 태그는 적재 때 하나로 합쳐진다
        Map<AmenityCode, Long> distinct = data.amenities().stream()
                .collect(Collectors.toMap(a -> a.sourceType() + "|" + a.sourceKey() + "|" + a.code(), Function.identity(), (a, b) -> b))
                .values().stream().collect(Collectors.groupingBy(a -> a.code(), Collectors.counting()));
        assertThat(distinct).containsEntry(AmenityCode.PARKING, 17L).containsEntry(AmenityCode.RENTAL_ROOM, 18L);
        assertThat(byCode.keySet()).containsExactlyInAnyOrder(AmenityCode.PARKING, AmenityCode.RENTAL_ROOM);
    }

    @Test
    void 원천_키는_중복이_없다() {
        assertThat(data.facilities().stream().map(f -> f.getSourceType() + "|" + f.getSourceKey()).distinct().count())
                .isEqualTo(data.facilities().size());
    }
}
