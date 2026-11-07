package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.FacilityType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityTypeRepository extends JpaRepository<FacilityType, Short> {

    /** type_id는 하드코딩하지 않고 name으로 찾는다. */
    Optional<FacilityType> findByName(String name);
}
