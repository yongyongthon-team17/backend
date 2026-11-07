package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilitySpaceId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilitySpaceRepository extends JpaRepository<FacilitySpace, FacilitySpaceId> {
}
