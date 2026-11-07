package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SourceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    /**
     * A02 후보 시설 조회. 선택 조건은 null 대신 boolean 플래그로 받는다
     * (PostgreSQL에서 ':p is null' 타입 추론 문제 회피). 호출부는 꺼진 조건에도 더미값(빈 문자열, [-1])을 넣는다.
     * keywordPattern은 이미 '%..%'로 감싸고 '\', '%', '_'를 이스케이프한 값이다(키워드 없으면 '%').
     */
    @Query("""
            select f from Facility f join fetch f.type t
            where (:allDistricts = true or f.district = :district)
              and (:allTypes = true or t.typeId in :typeIds)
              and (lower(f.name) like lower(:keywordPattern) escape '\\'
                   or lower(f.address) like lower(:keywordPattern) escape '\\')
            order by f.facilityId
            """)
    List<Facility> search(@Param("allDistricts") boolean allDistricts,
                          @Param("district") String district,
                          @Param("allTypes") boolean allTypes,
                          @Param("typeIds") Collection<Short> typeIds,
                          @Param("keywordPattern") String keywordPattern);

    /** A03 상세: type을 함께 가져와 별도 쿼리를 막는다 (type은 LAZY) */
    @Query("select f from Facility f join fetch f.type where f.facilityId = :facilityId")
    Optional<Facility> findDetailById(@Param("facilityId") Long facilityId);

    /** B02 재적재 UPSERT용 (R17) */
    Optional<Facility> findBySourceTypeAndSourceKey(SourceType sourceType, String sourceKey);
}
