package com.jigeumgayong.backend.facility.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A02 응답 data (06 A02). DaySchedule은 하루 여러 구간을 위해 intervals 배열을 쓴다(승인된 변경).
 * 조건(요일·구·유형·키워드)에 맞는 모든 시설을 돌려주고, 시설·공간마다 이용 상태(AVAILABLE/UNAVAILABLE/UNKNOWN)로 구분한다.
 *
 * @param totalCount        반환한 전체 시설 수
 * @param availableCount    희망 시간에 이용 가능한 시설 수 ("이용 가능한 시설 N곳")
 * @param matchedSpaceCount 이용 가능한 공간 수
 * @param unknownSpaceCount 선택 요일 운영시간을 알 수 없는 공간 수
 */
public record FacilitySearchResponse(
        Criteria criteria,
        List<Item> items,
        int totalCount,
        int availableCount,
        int matchedSpaceCount,
        int unknownSpaceCount) {

    /** 06 3.6 Criteria. keyword/district만 null 가능 */
    public record Criteria(String keyword, String district, List<Integer> facilityTypeIds,
                           int dayOfWeek, String startTime, String endTime) {
    }

    /**
     * FacilityBase + distanceMeters + availabilityStatus + availableSpaces. 거리는 A02에서 항상 null.
     * availabilityStatus: 공간 중 하나라도 이용 가능하면 AVAILABLE, 아니면 닫힌 공간이 있으면 UNAVAILABLE, 전부 모르면 UNKNOWN.
     * availableSpaces: 이름과 달리 시설의 모든 공간이며 공간별 availability.status로 구분한다(필드명은 호환을 위해 유지).
     */
    public record Item(String facilityId, int typeId, String typeName, String district, String accessType,
                       String name, String address, String imageUrl, Coordinates coordinates,
                       Double distanceMeters, String availabilityStatus, List<SpaceAvailability> availableSpaces) {
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
