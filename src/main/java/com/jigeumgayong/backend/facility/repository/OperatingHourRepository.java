package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.OperatingHourId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatingHourRepository extends JpaRepository<OperatingHour, OperatingHourId> {

    /** B02 재적재: 시설의 운영시간을 지우고 다시 넣는다 (R17) */
    @Modifying
    @Query("delete from OperatingHour h where h.id.facilityId = :facilityId")
    void deleteByFacilityId(@Param("facilityId") Long facilityId);
}
