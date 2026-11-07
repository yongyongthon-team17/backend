package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시설 공간 (docs/10 §5). 모든 시설은 1행 이상(ETL에서 검증). facility FK는 DDL이 책임진다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "facility_space")
public class FacilitySpace {

    @EmbeddedId
    private FacilitySpaceId id;

    /** 화면 표시명 (종합자료실 / 열람실 / 시설 전체) */
    @Column(name = "space_name", nullable = false, length = 30)
    private String spaceName;

    public FacilitySpace(Long facilityId, SpaceType spaceType, String spaceName) {
        this.id = new FacilitySpaceId(facilityId, spaceType);
        this.spaceName = spaceName;
    }

    public void changeSpaceName(String spaceName) {
        this.spaceName = spaceName;
    }
}
