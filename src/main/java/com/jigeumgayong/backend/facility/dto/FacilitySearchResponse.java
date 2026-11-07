package com.jigeumgayong.backend.facility.dto;

import java.math.BigDecimal;
import java.util.List;

/** A02 응답 data (06 A02). DaySchedule은 하루 여러 구간을 위해 intervals 배열을 쓴다(승인된 변경). */
public record FacilitySearchResponse(
        Criteria criteria,
        List<Item> items,
        int totalCount,
        int matchedSpaceCount,
        int unknownSpaceCount) {

    /** 06 3.6 Criteria. keyword/district만 null 가능 */
    public record Criteria(String keyword, String district, List<Integer> facilityTypeIds,
                           int dayOfWeek, String startTime, String endTime) {
    }

    /** FacilityBase + distanceMeters + availableSpaces. 거리는 A02에서 항상 null */
    public record Item(String facilityId, int typeId, String typeName, String district, String accessType,
                       String name, String address, String imageUrl, Coordinates coordinates,
                       Double distanceMeters, List<SpaceAvailability> availableSpaces) {
    }

    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {
    }

    public record SpaceAvailability(String spaceType, String spaceLabel, DaySchedule selectedSchedule,
                                    Availability availability) {
    }

    public record DaySchedule(int dayOfWeek, String state, List<TimeInterval> intervals) {
    }

    public record TimeInterval(String openTime, String closeTime) {
    }

    public record Availability(String status, String basis, boolean requiresReservation,
                               List<Gap> unavailableIntervals) {
    }

    public record Gap(String startTime, String endTime) {
    }
}
