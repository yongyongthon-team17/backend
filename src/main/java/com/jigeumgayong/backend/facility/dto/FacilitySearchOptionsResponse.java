package com.jigeumgayong.backend.facility.dto;

import java.util.List;

/** A01 응답 data (06 A01). chip_group은 명세 개정 전까지 포함하지 않는다. */
public record FacilitySearchOptionsResponse(
        List<FacilityTypeOption> facilityTypes,
        List<String> districts,
        List<DayOption> daysOfWeek,
        int defaultDayOfWeek,
        String timeZone) {

    public record FacilityTypeOption(int typeId, String name, String defaultImageUrl) {
    }

    /** value: 0=월 … 6=일 */
    public record DayOption(int value, String label) {
    }
}