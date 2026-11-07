package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilityAmenityId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityAmenityRepository extends JpaRepository<FacilityAmenity, FacilityAmenityId> {

    /** A03: 한 시설의 편의시설 태그 */
    List<FacilityAmenity> findByIdFacilityId(Long facilityId);
}
