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
public class FacilityAmenityId implements Serializable {

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "amenity_code", nullable = false, length = 20)
    private AmenityCode amenityCode;

    public FacilityAmenityId(Long facilityId, AmenityCode amenityCode) {
        this.facilityId = facilityId;
        this.amenityCode = amenityCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FacilityAmenityId that)) return false;
        return Objects.equals(facilityId, that.facilityId) && amenityCode == that.amenityCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(facilityId, amenityCode);
    }
}
