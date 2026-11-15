package com.jigeumgayong.backend.busarrival.service;

import com.jigeumgayong.backend.busarrival.dto.BusArrivalResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 시설 기준 메모리 캐시. 재시작하면 비워지고, 키가 시설 수만큼만 생겨 만료 항목은 다음 저장 때 덮어쓴다. */
class ArrivalCache {

    private record Entry(BusArrivalResponse value, Instant expiresAt) {
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    ArrivalCache(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    BusArrivalResponse get(long facilityId) {
        Entry e = entries.get(facilityId);
        if (e == null || !clock.instant().isBefore(e.expiresAt())) {
            return null;
        }
        return e.value();
    }

    void put(long facilityId, BusArrivalResponse value) {
        entries.put(facilityId, new Entry(value, clock.instant().plus(ttl)));
    }
}
