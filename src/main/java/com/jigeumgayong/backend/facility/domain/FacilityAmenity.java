package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 편의시설 태그 (docs/10 §4). facility FK(ON DELETE CASCADE)는 DDL이 책임진다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "facility_amenity")
public class FacilityAmenity {

    @EmbeddedId
    private FacilityAmenityId id;

    /** 표시 문구 (예: 주차 120면) */
    @Column(name = "detail", length = 100)
    private String detail;

    public FacilityAmenity(Long facilityId, AmenityCode amenityCode, String detail) {
        this.id = new FacilityAmenityId(facilityId, amenityCode);
        this.detail = detail;
    }

    public void changeDetail(String detail) {
        this.detail = detail;
    }
}
