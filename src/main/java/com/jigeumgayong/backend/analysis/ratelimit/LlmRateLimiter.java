package com.jigeumgayong.backend.analysis.ratelimit;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AI(LLM) 호출 한도. 슬라이딩 윈도우 로그 방식으로, 윈도우(기본 3분) 안의 호출 시각을 키별로 쌓아 센다.
 * 개인·IP·서버 전체 세 한도를 한꺼번에 검사하고 <b>모두 통과할 때만</b> 소모한다(하나라도 막히면 아무것도 소모하지 않음).
 *
 * <p>서버 메모리에만 두므로 재시작하면 초기화되고 서버가 여러 대이면 대수만큼 늘어난다(현재 EC2 1대 전제).
 * 키 수가 상한을 넘으면 만료 기록을 정리하고, 그래도 가득 차면 새 UUID는 추적하지 않고 IP로만 센다
 * (UUID를 무한히 만들어 메모리를 채우는 공격 방지).
 */
@Component
public class LlmRateLimiter {

    /** @param retryAfterSeconds 거부일 때 다시 시도할 수 있기까지 남은 초(올림, 최소 1). 허용이면 0 */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final Clock clock;
    private final RateLimitProperties props;

    private final Map<String, ArrayDeque<Long>> clients = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> ips = new HashMap<>();
    private final ArrayDeque<Long> global = new ArrayDeque<>();

    public LlmRateLimiter(Clock clock, RateLimitProperties props) {
        this.clock = clock;
        this.props = props;
    }

    /** OpenAI를 호출하기 직전에 한 번 부른다. 허용되면 이미 한 건 소모된 것이다(호출이 실패해도 반환하지 않는다). */
    public synchronized Decision tryAcquire(ClientKey key) {
        if (!props.enabled()) {
            return Decision.ALLOWED;
        }
        long now = clock.millis();
        long window = props.window().toMillis();

        String personal = key.personalKey();
        if (!clients.containsKey(personal) && clients.size() >= props.maxTrackedKeys()) {
            purge(clients, now, window);
            if (clients.size() >= props.maxTrackedKeys()) {
                personal = "i:" + key.ip(); // 가득 참: 새 UUID는 추적하지 않고 IP를 개인으로 본다
            }
        }
        if (!ips.containsKey(key.ip()) && ips.size() >= props.maxTrackedKeys()) {
            purge(ips, now, window);
            if (ips.size() >= props.maxTrackedKeys()) {
                return new Decision(false, window / 1000 > 0 ? window / 1000 : 1); // 메모리 보호: 닫는 쪽으로 실패
            }
        }

        long retry = Math.max(wait(clients, personal, props.perClient(), now, window),
                Math.max(wait(ips, key.ip(), props.perIp(), now, window),
                        waitGlobal(props.global(), now, window)));
        if (retry > 0) {
            return new Decision(false, retry);
        }
        if (props.perClient() > 0) {
            clients.computeIfAbsent(personal, k -> new ArrayDeque<>()).addLast(now);
        }
        if (props.perIp() > 0) {
            ips.computeIfAbsent(key.ip(), k -> new ArrayDeque<>()).addLast(now);
        }
        if (props.global() > 0) {
            global.addLast(now);
        }
        return Decision.ALLOWED;
    }

    /** 이 키가 한도에 걸려 있으면 다시 시도할 수 있기까지의 초, 아니면 0 */
    private long wait(Map<String, ArrayDeque<Long>> map, String key, int limit, long now, long window) {
        if (limit <= 0) {
            return 0;
        }
        ArrayDeque<Long> q = map.get(key);
        if (q == null) {
            return 0;
        }
        expire(q, now, window);
        if (q.isEmpty()) {
            map.remove(key);
            return 0;
        }
        return q.size() >= limit ? secondsUntilFree(q.peekFirst(), now, window) : 0;
    }

    private long waitGlobal(int limit, long now, long window) {
        if (limit <= 0) {
            return 0;
        }
        expire(global, now, window);
        return global.size() >= limit ? secondsUntilFree(global.peekFirst(), now, window) : 0;
    }

    private static void expire(ArrayDeque<Long> q, long now, long window) {
        while (!q.isEmpty() && q.peekFirst() <= now - window) {
            q.pollFirst();
        }
    }

    private static long secondsUntilFree(long oldest, long now, long window) {
        long millis = oldest + window - now;
        return Math.max(1, (millis + 999) / 1000);
    }

    private static void purge(Map<String, ArrayDeque<Long>> map, long now, long window) {
        map.values().forEach(q -> expire(q, now, window));
        map.values().removeIf(ArrayDeque::isEmpty);
    }
}
