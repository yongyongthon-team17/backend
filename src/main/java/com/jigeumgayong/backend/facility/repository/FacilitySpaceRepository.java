package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilitySpaceId;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilitySpaceRepository extends JpaRepository<FacilitySpace, FacilitySpaceId> {

    /** B02 재적재: 원천에서 사라진 공간을 지운다 */
    @Modifying
    @Query("delete from FacilitySpace s where s.id.facilityId = :facilityId and s.id.spaceType not in :keep")
    void deleteByFacilityIdExcept(@Param("facilityId") Long facilityId, @Param("keep") Collection<SpaceType> keep);
}
