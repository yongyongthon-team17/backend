package com.jigeumgayong.backend.busarrival.service;

import com.jigeumgayong.backend.busarrival.client.BusApiException;
import com.jigeumgayong.backend.busarrival.client.BusApiProperties;
import com.jigeumgayong.backend.busarrival.client.BusArrivalClient;
import com.jigeumgayong.backend.busarrival.client.RouteArrival;
import com.jigeumgayong.backend.busarrival.config.BusArrivalConfig;
import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse;
import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse.Arrival;
import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse.Status;
import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse.Stop;
import com.jigeumgayong.backend.busarrival.service.NearbyStopFinder.NearbyStop;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * A06 시설 주변 실시간 버스 도착. 외부 API가 실패해도 예외를 밖으로 던지지 않고 status로 알린다.
 * 순서: 시설 확인(404) → 스위치(NOT_ENABLED) → 캐시 → 키 확인 → 정류장 선택(CSV) → 정류장별 병렬 조회.
 */
@Slf4j
@Service
public class BusArrivalService {

    static final int RADIUS_METERS = 500;
    static final int MAX_STOPS = 3;
    static final int MAX_ARRIVALS = 5;

    // A03·A05와 같은 규칙: 양수 10진수 문자열
    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9][0-9]*$");

    private final FacilityRepository facilityRepository;
    private final BusArrivalClient client;
    private final BusApiProperties props;
    private final ExecutorService executor;
    private final NearbyStopFinder stopFinder;
    private final ArrivalCache cache;

    public BusArrivalService(FacilityRepository facilityRepository, BusArrivalClient client,
                             BusApiProperties props,
                             @Qualifier(BusArrivalConfig.BUS_EXECUTOR) ExecutorService executor, Clock clock) {
        this.facilityRepository = facilityRepository;
        this.client = client;
        this.props = props;
        this.executor = executor;
        this.stopFinder = NearbyStopFinder.fromSeed();
        this.cache = new ArrivalCache(clock, props.cacheTtl());
    }

    public BusArrivalResponse get(String facilityIdText) {
        long facilityId = parseFacilityId(facilityIdText);
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));
        String id = String.valueOf(facilityId);

        if (!props.realtimeEnabled()) {
            return new BusArrivalResponse(id, Status.NOT_ENABLED, RADIUS_METERS, List.of());
        }
        BusArrivalResponse cached = cache.get(facilityId);
        if (cached != null) {
            return cached;
        }
        if (!props.hasApiKey()) {
            log.warn("버스 도착 조회 불가: APP_BUS_API_KEY 미설정");
            return new BusArrivalResponse(id, Status.UNAVAILABLE, RADIUS_METERS, List.of());
        }

        List<NearbyStop> nearby = stopFinder.find(f.getLatitude().doubleValue(), f.getLongitude().doubleValue(),
                RADIUS_METERS, MAX_STOPS);
        BusArrivalResponse result = fetchAll(id, nearby);
        // 실패 결과는 캐시하지 않는다(다음 요청에서 바로 재시도)
        if (result.status() == Status.AVAILABLE) {
            cache.put(facilityId, result);
        }
        return result;
    }

    /** 정류장별로 병렬 호출. 일부 실패는 그 정류장 arrivals만 비우고, 전부 실패하면 UNAVAILABLE. */
    private BusArrivalResponse fetchAll(String facilityId, List<NearbyStop> nearby) {
        long timeoutMs = props.timeout().toMillis();
        List<CompletableFuture<List<RouteArrival>>> futures = nearby.stream()
                .map(s -> CompletableFuture.supplyAsync(() -> client.fetchArrivals(s.stopId()), executor)
                        // 클라이언트의 연결·읽기 타임아웃과 별개로, 호출당 전체 제한 시간을 여기서도 건다
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS))
                .toList();

        List<Stop> stops = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < nearby.size(); i++) {
            NearbyStop s = nearby.get(i);
            List<Arrival> arrivals;
            try {
                arrivals = toArrivals(futures.get(i).join());
            } catch (RuntimeException e) {
                failed++;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                // 외부 예외 메시지에는 키가 든 URI가 있을 수 있어, 우리가 만든 사유(BusApiException)만 남긴다
                log.warn("버스 도착 조회 실패 stopId={} reason={} {}", s.stopId(), cause.getClass().getSimpleName(),
                        cause instanceof BusApiException ? cause.getMessage() : "");
                arrivals = List.of();
            }
            stops.add(new Stop(s.stopId(), s.name(), s.distanceMeters(), arrivals));
        }
        if (!nearby.isEmpty() && failed == nearby.size()) {
            return new BusArrivalResponse(facilityId, Status.UNAVAILABLE, RADIUS_METERS, List.of());
        }
        return new BusArrivalResponse(facilityId, Status.AVAILABLE, RADIUS_METERS, stops);
    }

    /** 남은 시간순(정보 없음은 뒤), 최대 5개. 노선번호가 없는 항목은 표시할 수 없어 버린다 */
    private static List<Arrival> toArrivals(List<RouteArrival> raw) {
        return raw.stream()
                .filter(a -> a.routeName() != null)
                .sorted(Comparator.comparing(RouteArrival::minutesLeft,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_ARRIVALS)
                .map(a -> new Arrival(a.routeName(), a.minutesLeft(), a.stopsLeft()))
                .toList();
    }

    private static long parseFacilityId(String text) {
        if (text == null || !POSITIVE_INT.matcher(text).matches()) {
            throw invalidRequest(ErrorDetail.INVALID_FORMAT);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalidRequest(ErrorDetail.OUT_OF_RANGE);
        }
    }

    private static BusinessException invalidRequest(String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail("facilityId", reason)));
    }
}
