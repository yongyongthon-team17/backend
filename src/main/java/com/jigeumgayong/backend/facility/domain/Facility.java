package com.jigeumgayong.backend.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

/** 공공시설 (docs/10 §3). CHECK(좌표 범위, district 등)는 DDL이 책임진다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "facility",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_facility_source", columnNames = {"source_type", "source_key"}),
        indexes = @Index(name = "idx_facility_district_type", columnList = "district, type_id"))
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "facility_id")
    private Long facilityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private FacilityType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    @Column(name = "source_key", nullable = false, length = 100)
    private String sourceKey;

    @Column(name = "district", nullable = false, length = 10)
    private String district;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 20)
    private AccessType accessType;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "phone", length = 20)
    private String phone;

    /** 휴관일 원문. 표시 전용 (R07) */
    @Column(name = "closed_day_note", length = 255)
    private String closedDayNote;

    /** 반경 500m 정류장 수. null=미확인, 0=확인 결과 0곳 (R14) */
    @Column(name = "bus_stop_count")
    private Integer busStopCount;

    @Column(name = "bus_counted_at")
    private LocalDate busCountedAt;

    /** 원천 데이터 기준일 (R15) */
    @Column(name = "source_updated_at")
    private LocalDate sourceUpdatedAt;

    /** 적재·갱신 시각. INSERT와 UPDATE 모두 앱에서 채운다 (R15) */
    @UpdateTimestamp
    @Column(name = "loaded_at", nullable = false)
    private LocalDateTime loadedAt;

    @Builder
    private Facility(FacilityType type, SourceType sourceType, String sourceKey, String district,
                     AccessType accessType, String name, String address,
                     BigDecimal latitude, BigDecimal longitude, String phone, String closedDayNote,
                     Integer busStopCount, LocalDate busCountedAt, LocalDate sourceUpdatedAt) {
        this.type = type;
        this.sourceType = sourceType;
        this.sourceKey = sourceKey;
        this.district = District.require(district);
        this.accessType = accessType == null ? AccessType.OPEN_HOURS : accessType;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.phone = phone;
        this.closedDayNote = closedDayNote;
        this.busStopCount = busStopCount;
        this.busCountedAt = busCountedAt;
        this.sourceUpdatedAt = sourceUpdatedAt;
    }

    /** 교통 적재용. 정류장 계산 결과(수, 계산일)를 한 번에 바꾼다. */
    public void updateBusStops(Integer busStopCount, LocalDate busCountedAt) {
        this.busStopCount = busStopCount;
        this.busCountedAt = busCountedAt;
    }

    /**
     * B02 재적재 UPSERT용 (R17). (source_type, source_key)로 찾은 기존 행을 덮어쓴다.
     * 키 컬럼(source_type, source_key)과 PK는 바꾸지 않는다.
     * 값이 전부 같아도 loaded_at은 갱신한다(적재 시각이므로 dirty 상태를 강제).
     */
    public void updateFromSource(FacilityType type, String district, AccessType accessType,
                                 String name, String address, BigDecimal latitude, BigDecimal longitude,
                                 String phone, String closedDayNote, Integer busStopCount,
                                 LocalDate busCountedAt, LocalDate sourceUpdatedAt) {
        this.type = type;
        this.district = District.require(district);
        this.accessType = accessType == null ? AccessType.OPEN_HOURS : accessType;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.phone = phone;
        this.closedDayNote = closedDayNote;
        this.busStopCount = busStopCount;
        this.busCountedAt = busCountedAt;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.loadedAt = LocalDateTime.now();
    }
}
