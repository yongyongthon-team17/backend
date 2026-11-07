package com.jigeumgayong.backend.facility.repository;

import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.OperatingHourId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperatingHourRepository extends JpaRepository<OperatingHour, OperatingHourId> {

    /** B02 재적재: 시설의 운영시간을 지우고 다시 넣는다 (R17) */
    @Modifying
    @Query("delete from OperatingHour h where h.id.facilityId = :facilityId")
    void deleteByFacilityId(@Param("facilityId") Long facilityId);

    /** A02: 후보 시설들의 선택 요일 운영시간을 한 번에 조회 (0=월 … 6=일) */
    @Query("""
            select h from OperatingHour h
            where h.id.facilityId in :facilityIds and h.id.dayOfWeek = :dayOfWeek
            order by h.id.facilityId, h.id.spaceType, h.id.intervalSeq
            """)
    List<OperatingHour> findByFacilityIdsAndDay(@Param("facilityIds") Collection<Long> facilityIds,
                                                @Param("dayOfWeek") Short dayOfWeek);
}
