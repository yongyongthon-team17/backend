package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.etl.FacilityLoader.LoadResult;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.support.H2PostgresJpaTest;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 CSV를 H2에 적재해 건수, 재적재 시 facility_id 유지(R17), 정류장 수 보존을 확인한다. */
@H2PostgresJpaTest
@Import(FacilityLoader.class)
class FacilityLoaderTest {

    @Autowired FacilityLoader loader;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void 실제_CSV를_적재하면_기대_건수가_들어간다() {
        SeedData data = new SeedBuilder().build();

        LoadResult result = loader.load(data);
        em.flush();

        assertThat(result.created()).isEqualTo(348);
        assertThat(result.updated()).isZero();
        assertThat(count("facility")).isEqualTo(348);
        assertThat(count("facility_type")).isEqualTo(6);
        assertThat(count("operating_hour")).isEqualTo(2514);
        assertThat(count("facility_amenity")).isEqualTo(35); // PARKING 17 + RENTAL_ROOM 18
        Map<String, Long> amenities = jdbc.queryForList("select amenity_code, count(*) c from facility_amenity group by amenity_code")
                .stream().collect(Collectors.toMap(r -> (String) r.get("amenity_code"), r -> ((Number) r.get("c")).longValue()));
        assertThat(amenities).containsEntry(AmenityCode.PARKING.name(), 17L).containsEntry(AmenityCode.RENTAL_ROOM.name(), 18L);
        // 공간 없는 시설 0
        assertThat(jdbc.queryForObject("select count(*) from facility f where not exists "
                + "(select 1 from facility_space s where s.facility_id = f.facility_id)", Long.class)).isZero();
        // 도서관 유형 시설 = LIB_STD 25 + LIB_SMALL 180
        assertThat(jdbc.queryForObject("select count(*) from facility f join facility_type t on f.type_id = t.type_id "
                + "where t.name = '도서관'", Long.class)).isEqualTo(205);
    }

    @Test
    void 재적재해도_facility_id와_건수가_그대로이고_정류장_수는_지워지지_않는다() {
        SeedData data = new SeedBuilder().build();
        loader.load(data);
        em.flush();
        Map<String, Long> before = idsBySourceKey();
        long spacesBefore = count("facility_space");

        Facility sample = facilityRepository.findBySourceTypeAndSourceKey(SourceType.LIB_STD, "LIB-용인중앙도서관").orElseThrow();
        jdbc.update("update facility set bus_stop_count = 5 where facility_id = ?", sample.getFacilityId());
        em.clear();

        LoadResult second = loader.load(data);
        em.flush();
        em.clear();

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(348);
        assertThat(idsBySourceKey()).isEqualTo(before);
        assertThat(count("operating_hour")).isEqualTo(2514);
        assertThat(count("facility_space")).isEqualTo(spacesBefore);
        assertThat(count("facility_amenity")).isEqualTo(35);
        assertThat(count("facility_type")).isEqualTo(6);
        assertThat(jdbc.queryForObject("select bus_stop_count from facility where facility_id = ?", Integer.class,
                sample.getFacilityId())).isEqualTo(5);
    }

    private Map<String, Long> idsBySourceKey() {
        return jdbc.queryForList("select source_type, source_key, facility_id from facility").stream()
                .collect(Collectors.toMap(r -> r.get("source_type") + "|" + r.get("source_key"),
                        r -> ((Number) r.get("facility_id")).longValue()));
    }
}
