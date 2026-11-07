package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilityAmenityId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityAmenityRepository extends JpaRepository<FacilityAmenity, FacilityAmenityId> {
}
