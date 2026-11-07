package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공간·요일·구간별 운영시간 (docs/10 §6).
 * (facility_id, space_type) → facility_space 복합 FK와 OPEN/CLOSED CHECK는 DDL이 책임진다.
 * 시각은 자정부터의 분(0~1440). 24:00 = 1440.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "operating_hour",
        indexes = @Index(name = "idx_operating_hour_search",
                columnList = "day_of_week, day_state, open_min, close_min"))
public class OperatingHour {

    @EmbeddedId
    private OperatingHourId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_state", nullable = false, length = 10)
    private DayState dayState;

    /** OPEN일 때만 값이 있음 (0~1439) */
    @Column(name = "open_min")
    private Short openMin;

    /** OPEN일 때만 값이 있음 (1~1440) */
    @Column(name = "close_min")
    private Short closeMin;

    @Builder
    private OperatingHour(Long facilityId, SpaceType spaceType, Short dayOfWeek, Short intervalSeq,
                          DayState dayState, Short openMin, Short closeMin) {
        Short seq = intervalSeq != null ? intervalSeq : Short.valueOf((short) 1);
        this.id = new OperatingHourId(facilityId, spaceType, dayOfWeek, seq);
        this.dayState = dayState;
        this.openMin = openMin;
        this.closeMin = closeMin;
    }
}
