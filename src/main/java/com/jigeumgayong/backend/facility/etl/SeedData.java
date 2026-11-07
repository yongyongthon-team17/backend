package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.AmenityCode;
import com.jigeumgayong.backend.facility.domain.SourceType;
import java.util.List;
import java.util.Map;

/**
 * 검증을 통과한 적재 대상 묶음.
 *
 * @param facilities 적재할 시설
 * @param quarantine 격리된 행과 사유 (좌표 없음·범위 밖 등, R16)
 * @param amenities  편의시설 태그. 적용 순서대로이며 같은 시설·코드가 겹치면 뒤의 것이 이긴다
 * @param notes      원천별 처리 메모 건수 (로그용)
 */
record SeedData(List<SourceFacility> facilities, List<Quarantined> quarantine,
                List<AmenityAssignment> amenities, Map<String, Integer> notes) {

    record Quarantined(SourceType sourceType, String sourceKey, String name, String address, String reason) {
    }

    record AmenityAssignment(SourceType sourceType, String sourceKey, AmenityCode code, String detail) {
    }
}
