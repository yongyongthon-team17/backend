package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SourceType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    /** B02 재적재 UPSERT용 (R17) */
    Optional<Facility> findBySourceTypeAndSourceKey(SourceType sourceType, String sourceKey);
}
