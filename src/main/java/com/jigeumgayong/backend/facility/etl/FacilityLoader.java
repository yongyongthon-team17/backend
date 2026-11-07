package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilityAmenity;
import com.jigeumgayong.backend.facility.domain.FacilityAmenityId;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilitySpaceId;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.HourParser.Slot;
import com.jigeumgayong.backend.facility.etl.SeedData.AmenityAssignment;
import com.jigeumgayong.backend.facility.etl.SourceFacility.SpaceData;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검증된 시드를 DB에 넣는다. 재적재해도 facility_id가 유지되도록 (source_type, source_key)로 UPSERT하고 (R17),
 * 운영시간은 시설별로 지운 뒤 다시 넣는다. 하나의 트랜잭션이라 실패하면 전부 되돌아간다.
 */
@Service
@RequiredArgsConstructor
public class FacilityLoader {

    /** 적재 결과 건수 */
    public record LoadResult(int created, int updated, int spaces, int hourRows, int amenities) {
    }

    private final FacilityTypeRepository typeRepository;
    private final FacilityRepository facilityRepository;
    private final FacilitySpaceRepository spaceRepository;
    private final OperatingHourRepository hourRepository;
    private final FacilityAmenityRepository amenityRepository;

    @Transactional
    public LoadResult load(SeedData data) {
        Map<String, FacilityType> types = ensureTypes();
        Map<String, Long> ids = new HashMap<>();
        int created = 0;
        int updated = 0;
        int spaces = 0;
        int hourRows = 0;

        for (SourceFacility f : data.facilities()) {
            FacilityType type = types.get(f.getTypeName());
            BigDecimal lat = scale(f.getLat());
            BigDecimal lng = scale(f.getLng());
            Facility facility = facilityRepository
                    .findBySourceTypeAndSourceKey(f.getSourceType(), f.getSourceKey()).orElse(null);
            if (facility == null) {
                facility = facilityRepository.save(Facility.builder()
                        .type(type).sourceType(f.getSourceType()).sourceKey(f.getSourceKey())
                        .district(f.getDistrict()).accessType(f.getAccessType())
                        .name(f.getName()).address(f.getAddress()).latitude(lat).longitude(lng)
                        .phone(f.getPhone()).closedDayNote(f.getNote()).sourceUpdatedAt(f.getUpdated())
                        .busStopCount(f.getBusStopCount()).busCountedAt(f.getBusCountedAt())
                        .build());
                created++;
            } else {
                // 정류장 정보는 아래에서 따로 처리한다. 계산 결과가 없을 때 기존 값이 지워지지 않게 현재 값을 넘긴다
                facility.updateFromSource(type, f.getDistrict(), f.getAccessType(), f.getName(), f.getAddress(),
                        lat, lng, f.getPhone(), f.getNote(), facility.getBusStopCount(),
                        facility.getBusCountedAt(), f.getUpdated());
                // 이번에 계산한 값이 있을 때만 덮어쓴다 (계산 실패·파일 없음 → 기존 값 보존)
                if (f.getBusStopCount() != null) {
                    facility.updateBusStops(f.getBusStopCount(), f.getBusCountedAt());
                }
                updated++;
            }
            Long facilityId = facility.getFacilityId();
            ids.put(key(f.getSourceType().name(), f.getSourceKey()), facilityId);
            spaces += f.getSpaces().size();
            hourRows += replaceSpacesAndHours(facilityId, f);
        }

        int amenities = 0;
        for (AmenityAssignment a : data.amenities()) {
            Long facilityId = ids.get(key(a.sourceType().name(), a.sourceKey()));
            FacilityAmenityId id = new FacilityAmenityId(facilityId, a.code());
            FacilityAmenity existing = amenityRepository.findById(id).orElse(null);
            if (existing == null) {
                amenityRepository.save(new FacilityAmenity(facilityId, a.code(), a.detail()));
                amenities++;
            } else {
                existing.changeDetail(a.detail());
            }
        }
        return new LoadResult(created, updated, spaces, hourRows, amenities);
    }

    /** 시설 유형 코드값이 없으면 만든다. 이미 있으면 그대로 둔다. */
    private Map<String, FacilityType> ensureTypes() {
        Map<String, FacilityType> types = new HashMap<>();
        for (DefaultFacilityTypes.Entry e : DefaultFacilityTypes.ALL) {
            FacilityType type = typeRepository.findByName(e.name()).orElseGet(() -> typeRepository.save(
                    FacilityType.builder().name(e.name()).chipGroup(e.chipGroup()).defaultImage(e.defaultImage())
                            .build()));
            types.put(e.name(), type);
        }
        return types;
    }

    /** 공간·운영시간을 원천 기준으로 맞춘다. 반환값은 넣은 operating_hour 행 수. */
    private int replaceSpacesAndHours(Long facilityId, SourceFacility f) {
        hourRepository.deleteByFacilityId(facilityId);
        spaceRepository.deleteByFacilityIdExcept(facilityId, new ArrayList<>(f.getSpaces().keySet()));
        for (Map.Entry<SpaceType, SpaceData> e : f.getSpaces().entrySet()) {
            String spaceName = e.getValue().name();
            FacilitySpace existing = spaceRepository.findById(new FacilitySpaceId(facilityId, e.getKey())).orElse(null);
            if (existing == null) {
                spaceRepository.save(new FacilitySpace(facilityId, e.getKey(), spaceName));
            } else {
                existing.changeSpaceName(spaceName);
            }
        }
        // operating_hour는 (facility_id, space_type) 복합 FK로 공간을 참조하므로 공간을 먼저 DB에 내보낸다
        spaceRepository.flush();

        List<OperatingHour> rows = new ArrayList<>();
        for (Map.Entry<SpaceType, SpaceData> e : f.getSpaces().entrySet()) {
            Map<Integer, List<Slot>> hours = e.getValue().hours();
            if (hours == null) {
                continue; // ALWAYS_OPEN: 운영시간 행 없음
            }
            for (int day = 0; day < 7; day++) {
                int seq = 1;
                for (Slot slot : hours.get(day)) {
                    rows.add(OperatingHour.builder()
                            .facilityId(facilityId).spaceType(e.getKey())
                            .dayOfWeek((short) day).intervalSeq((short) seq++)
                            .dayState(slot.state())
                            .openMin(slot.openMin() == null ? null : slot.openMin().shortValue())
                            .closeMin(slot.closeMin() == null ? null : slot.closeMin().shortValue())
                            .build());
                }
            }
        }
        hourRepository.saveAll(rows);
        return rows.size();
    }

    private static BigDecimal scale(Double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static String key(String sourceType, String sourceKey) {
        return sourceType + "|" + sourceKey;
    }
}
