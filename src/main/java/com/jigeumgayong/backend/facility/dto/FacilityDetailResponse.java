package com.jigeumgayong.backend.facility.dto;

import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Availability;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import java.time.LocalDate;
import java.util.List;

/** A03 응답 data (06 A03). 운영시간 표현(DaySchedule)은 A02와 같고, 시설 상세 필드와 weeklyHours가 더해진다. */
public record FacilityDetailResponse(Criteria criteria, FacilityDetail facility) {

    /** dayOfWeek/startTime/endTime만 포함 */
    public record Criteria(int dayOfWeek, String startTime, String endTime) {
    }

    /** FacilityBase + 상세 필드. phone, closedDayNote, dataUpdatedAt은 null 가능(키는 유지) */
    public record FacilityDetail(String facilityId, int typeId, String typeName, String district,
                                 String accessType, String name, String address, String imageUrl,
                                 Coordinates coordinates, String phone, String closedDayNote,
                                 LocalDate dataUpdatedAt, List<String> amenities, List<SpaceDetail> spaces) {
    }

    /** SpaceAvailability + weeklyHours(요일순 7개) */
    public record SpaceDetail(String spaceType, String spaceLabel, DaySchedule selectedSchedule,
                              Availability availability, List<DaySchedule> weeklyHours) {
    }
}
