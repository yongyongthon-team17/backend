package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 원천 CSV 한 행을 DB에 넣기 좋게 정리한 시설. 좌표는 지오코딩 캐시로 채우므로 setter가 있다. */
@Getter
@Setter
@Builder
class SourceFacility {

    /** 공간 하나의 표시명과 요일별 운영시간. hours가 null이면 operating_hour 행을 만들지 않는다(ALWAYS_OPEN). */
    record SpaceData(String name, Map<Integer, List<Slot>> hours) {
    }

    private SourceType sourceType;
    private String sourceKey;
    /** facility_type.name (도서관, 생활체육시설, 주민자치센터 …) */
    private String typeName;
    private String name;
    private String address;
    private String district;
    private AccessType accessType;
    private String phone;
    /** closed_day_note: 휴관일 원문 등 표시 전용 (R07) */
    private String note;
    private LocalDate updated;
    private Double lat;
    private Double lng;
    /** 교통: 반경 500m 정류장 수와 계산일. 계산하지 못했으면 null (R14) */
    private Integer busStopCount;
    private LocalDate busCountedAt;
    /** 값이 있으면 격리 대상이다 (예: 수기 입력 hours 형식 오류) */
    private String invalidReason;
    @Builder.Default
    private Map<SpaceType, SpaceData> spaces = new LinkedHashMap<>();
}
