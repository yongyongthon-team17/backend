package com.jigeumgayong.backend.support;

import com.jigeumgayong.backend.busarrival.client.BusApiException;
import com.jigeumgayong.backend.busarrival.client.BusArrivalClient;
import com.jigeumgayong.backend.busarrival.client.RouteArrival;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 테스트용 버스 도착 클라이언트. 정류장별로 실패·지연을 지정할 수 있고 호출 횟수를 센다. */
public class FakeBusArrivalClient implements BusArrivalClient {

    public static final List<RouteArrival> DEFAULT_ARRIVALS = List.of(
            new RouteArrival("5005", 7, 4),
            new RouteArrival("10", 2, 1),
            new RouteArrival("66-4", null, null),
            new RouteArrival("820", 15, 9),
            new RouteArrival("1112", 4, 2),
            new RouteArrival("5500-1", 11, 6));

    private final AtomicInteger calls = new AtomicInteger();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> delays = new ConcurrentHashMap<>();
    private volatile boolean failAll;
    private volatile long delayAllMs;

    @Override
    public List<RouteArrival> fetchArrivals(String stopId) {
        calls.incrementAndGet();
        long delay = Math.max(delayAllMs, delays.getOrDefault(stopId, 0L));
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failAll || failing.contains(stopId)) {
            throw new BusApiException("HTTP 500");
        }
        return DEFAULT_ARRIVALS;
    }

    public void reset() {
        calls.set(0);
        failing.clear();
        delays.clear();
        failAll = false;
        delayAllMs = 0;
    }

    public int calls() {
        return calls.get();
    }

    public void failAll() {
        failAll = true;
    }

    public void fail(String stopId) {
        failing.add(stopId);
    }

    public void delayAll(long ms) {
        delayAllMs = ms;
    }
}
