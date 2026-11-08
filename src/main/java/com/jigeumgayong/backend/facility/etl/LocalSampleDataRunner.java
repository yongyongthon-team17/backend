package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.etl.SourceFacility.SpaceData;
import com.jigeumgayong.backend.global.response.Meta;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발 전용 샘플 시설. 실데이터(ETL)에 없는 케이스(ALWAYS_OPEN, RESERVATION, 정류장 수 NULL)만 채운다.
 * ApplicationReadyEvent는 모든 Runner(EtlRunner 포함)가 끝난 뒤 발행되므로 ETL 이후에 실행된다.
 * (source_type, source_key)로 UPSERT되어 여러 번 실행해도 중복이 생기지 않는다.
 * local 프로파일 + app.sample-data.enabled=true 일 때만 빈이 만들어진다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.sample-data.enabled", havingValue = "true")
class LocalSampleDataRunner {

    static final String KEY_PREFIX = "LOCAL-SAMPLE-";
    private static final String NOTE = "로컬 개발용 샘플 데이터 (실제 시설 아님, 정류장 수는 임의 값)";

    private final FacilityLoader loader;

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        loader.load(new SeedData(samples(), List.of(), List.of(), Map.of()));
        log.info("로컬 샘플 시설 적재: {}건 ({}001~003)", samples().size(), KEY_PREFIX);
    }

    static List<SourceFacility> samples() {
        LocalDate today = LocalDate.now(Meta.ZONE);
        // 001: 상시 개방. 운영시간 행 없음(hours=null)
        SourceFacility always = sample("001", DefaultFacilityTypes.SPORTS, "[샘플] 상시개방 체육공원",
                "경기도 용인시 수지구 샘플로 1 (샘플 주소)", AccessType.ALWAYS_OPEN, 37.3220, 127.0950, 4, today);
        always.getSpaces().put(SpaceType.DEFAULT, new SpaceData("시설 전체", null));

        // 002: 예약제. 운영시간이 있어야 A04 후보가 된다 (평일 09~21시, 주말 09~18시)
        SourceFacility reservation = sample("002", DefaultFacilityTypes.SPORTS, "[샘플] 예약제 체육관",
                "경기도 용인시 기흥구 샘플로 2 (샘플 주소)", AccessType.RESERVATION, 37.2750, 127.1150, 2, today);
        reservation.getSpaces().put(SpaceType.DEFAULT,
                new SpaceData("시설 전체", HourParser.parseSpec("0-4 09:00-21:00; 5-6 09:00-18:00")));

        // 003: 정류장 수 미확인(NULL → NO_DATA). 평일 09~18시, 주말 휴관
        SourceFacility noBus = sample("003", DefaultFacilityTypes.LIBRARY, "[샘플] 교통정보없음 도서관",
                "경기도 용인시 처인구 샘플로 3 (샘플 주소)", AccessType.OPEN_HOURS, 37.2340, 127.2010, null, null);
        noBus.getSpaces().put(SpaceType.DEFAULT,
                new SpaceData("시설 전체", HourParser.parseSpec("0-4 09:00-18:00; 5-6 CLOSED")));

        return List.of(always, reservation, noBus);
    }

    private static SourceFacility sample(String no, String typeName, String name, String address, AccessType access,
                                         double lat, double lng, Integer busStops, LocalDate busCountedAt) {
        return SourceFacility.builder()
                .sourceType(SourceType.MANUAL).sourceKey(KEY_PREFIX + no)
                .typeName(typeName).name(name).address(address)
                .district(EtlUtil.districtOf(address)).accessType(access)
                .note(NOTE).lat(lat).lng(lng)
                .busStopCount(busStops).busCountedAt(busCountedAt)
                .build();
    }
}
