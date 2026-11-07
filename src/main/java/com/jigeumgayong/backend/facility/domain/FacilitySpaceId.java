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
public class FacilitySpaceId implements Serializable {

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 20)
    private SpaceType spaceType;

    public FacilitySpaceId(Long facilityId, SpaceType spaceType) {
        this.facilityId = facilityId;
        this.spaceType = spaceType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FacilitySpaceId that)) return false;
        return Objects.equals(facilityId, that.facilityId) && spaceType == that.spaceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(facilityId, spaceType);
    }
}
