package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class OperatingHourId implements Serializable {

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 20)
    private SpaceType spaceType;

    /** 0=월 … 6=일 (DayOfWeek.getValue() - 1) */
    @Column(name = "day_of_week", nullable = false)
    private Short dayOfWeek;

    /** 같은 요일 안의 구간 번호, 1부터 (R05) */
    @Column(name = "interval_seq", nullable = false)
    private Short intervalSeq;

    public OperatingHourId(Long facilityId, SpaceType spaceType, Short dayOfWeek, Short intervalSeq) {
        this.facilityId = facilityId;
        this.spaceType = spaceType;
        this.dayOfWeek = dayOfWeek;
        this.intervalSeq = intervalSeq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OperatingHourId that)) return false;
        return Objects.equals(facilityId, that.facilityId)
                && spaceType == that.spaceType
                && Objects.equals(dayOfWeek, that.dayOfWeek)
                && Objects.equals(intervalSeq, that.intervalSeq);
    }

    @Override
    public int hashCode() {
        return Objects.hash(facilityId, spaceType, dayOfWeek, intervalSeq);
    }
}
