package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시설 유형 (docs/10 §2). type_id는 하드코딩하지 않고 name으로 조회한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "facility_type")
public class FacilityType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "type_id")
    private Short typeId;

    @Column(name = "name", nullable = false, unique = true, length = 30)
    private String name;

    @Column(name = "chip_group", nullable = false, length = 20)
    private String chipGroup;

    @Column(name = "default_image", length = 255)
    private String defaultImage;

    @Builder
    private FacilityType(String name, String chipGroup, String defaultImage) {
        this.name = name;
        this.chipGroup = ChipGroup.require(chipGroup);
        this.defaultImage = defaultImage;
    }
}
