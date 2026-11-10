package com.jigeumgayong.backend.analysis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.ratelimit.LlmRateLimiter.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 실제 OpenAI 호출 없이, 고정(조작 가능한) 시계로 한도 계산만 검증한다. */
class LlmRateLimiterTest {

    /** 테스트가 시간을 직접 움직이는 시계 */
    static class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }

    MutableClock clock = new MutableClock();

    private LlmRateLimiter limiter(int perClient, int perIp, int global) {
        return new LlmRateLimiter(clock,
                new RateLimitProperties(true, Duration.ofMinutes(3), perClient, perIp, global, 20000));
    }

    private static ClientKey person(String ip) {
        return new ClientKey(UUID.randomUUID().toString(), ip);
    }

    @Test
    void 개인_한도_10건까지_허용하고_11번째는_거부하며_남은_초를_알려준다() {
        LlmRateLimiter limiter = limiter(10, 150, 150);
        ClientKey me = person("1.1.1.1");

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(me).allowed()).as("%d번째", i + 1).isTrue();
        }
        Decision denied = limiter.tryAcquire(me);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(180); // 가장 오래된 기록이 3분 뒤에 풀림

        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire(me).retryAfterSeconds()).isEqualTo(120);
    }

    @Test
    void 윈도우가_지나면_다시_허용한다() {
        LlmRateLimiter limiter = limiter(2, 150, 150);
        ClientKey me = person("1.1.1.1");
        limiter.tryAcquire(me);
        limiter.tryAcquire(me);
        assertThat(limiter.tryAcquire(me).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(181));

        assertThat(limiter.tryAcquire(me).allowed()).isTrue();
    }

    @Test
    void 슬라이딩_윈도우라_오래된_기록부터_풀린다() {
        LlmRateLimiter limiter = limiter(4, 150, 150);
        ClientKey me = person("1.1.1.1");
        limiter.tryAcquire(me);
        limiter.tryAcquire(me); // t0에 2건
        clock.advance(Duration.ofSeconds(100));
        limiter.tryAcquire(me);
        limiter.tryAcquire(me); // t0+100초에 2건 → 4건 가득
        assertThat(limiter.tryAcquire(me).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(81)); // t0+181초: 처음 2건만 만료

        assertThat(limiter.tryAcquire(me).allowed()).isTrue();
        assertThat(limiter.tryAcquire(me).allowed()).isTrue();
        assertThat(limiter.tryAcquire(me).allowed()).isFalse();
    }

    @Test
    void UUID를_바꿔도_IP_상한에_걸린다() {
        LlmRateLimiter limiter = limiter(10, 15, 150);

        for (int i = 0; i < 15; i++) {
            assertThat(limiter.tryAcquire(person("2.2.2.2")).allowed()).isTrue(); // 매번 새 UUID
        }
        assertThat(limiter.tryAcquire(person("2.2.2.2")).allowed()).isFalse();
        assertThat(limiter.tryAcquire(person("3.3.3.3")).allowed()).as("다른 IP는 영향 없음").isTrue();
    }

    @Test
    void 서버_전체_상한은_누가_호출하든_적용된다() {
        LlmRateLimiter limiter = limiter(10, 150, 20);

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire(person("9.9.9." + i)).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire(person("8.8.8.8")).allowed()).isFalse();
    }

    @Test
    void 공유_IP에서도_개인_한도가_따로_적용된다() {
        LlmRateLimiter limiter = limiter(10, 150, 150);
        ClientKey a = person("7.7.7.7");
        ClientKey b = person("7.7.7.7"); // 같은 와이파이, 다른 사람
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire(a);
        }

        assertThat(limiter.tryAcquire(a).allowed()).isFalse();
        assertThat(limiter.tryAcquire(b).allowed()).isTrue();
    }

    @Test
    void 하나라도_막히면_다른_한도를_소모하지_않는다() {
        LlmRateLimiter limiter = limiter(2, 150, 3);
        ClientKey a = person("1.1.1.1");
        limiter.tryAcquire(a);
        limiter.tryAcquire(a); // 전체 2건 소모
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(a).allowed()).isFalse(); // 개인 한도에 막힘. 전체를 소모하면 안 된다
        }

        // 막힌 시도가 전체 한도를 소모했다면 여기서 거부된다
        assertThat(limiter.tryAcquire(person("2.2.2.2")).allowed()).isTrue();
        assertThat(limiter.tryAcquire(person("3.3.3.3")).allowed()).isFalse(); // 전체 3건 도달
    }

    @Test
    void UUID가_없으면_IP를_개인으로_센다() {
        LlmRateLimiter limiter = limiter(10, 150, 150);
        ClientKey noId = new ClientKey(null, "4.4.4.4"); // 헤더를 빼서 한도를 늘리지 못한다

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire(noId).allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire(noId).allowed()).isFalse();
        assertThat(limiter.tryAcquire(new ClientKey(null, "5.5.5.5")).allowed()).isTrue();
    }

    @Test
    void 비활성이면_항상_허용한다() {
        LlmRateLimiter off = new LlmRateLimiter(clock,
                new RateLimitProperties(false, Duration.ofMinutes(3), 1, 1, 1, 10));

        for (int i = 0; i < 100; i++) {
            assertThat(off.tryAcquire(person("1.1.1.1")).allowed()).isTrue();
        }
    }

    @Test
    void UUID_추적_키가_가득_차면_새_UUID는_IP로_묶어_센다() {
        LlmRateLimiter limiter = new LlmRateLimiter(clock,
                new RateLimitProperties(true, Duration.ofMinutes(3), 2, 150, 150, 3));
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(person("6.6.6.0")); // 같은 IP에서 서로 다른 UUID 3개로 UUID 추적 키를 채운다
        }

        // 가득 찬 뒤의 새 UUID 여러 개는 UUID별이 아니라 IP 하나의 개인 한도(2건)를 공유한다
        assertThat(limiter.tryAcquire(person("6.6.6.0")).allowed()).isTrue();
        assertThat(limiter.tryAcquire(person("6.6.6.0")).allowed()).isTrue();
        assertThat(limiter.tryAcquire(person("6.6.6.0")).allowed()).isFalse();
    }

    @Test
    void IP_추적_키가_가득_차면_새_IP는_메모리_보호를_위해_거부하고_시간이_지나면_풀린다() {
        LlmRateLimiter limiter = new LlmRateLimiter(clock,
                new RateLimitProperties(true, Duration.ofMinutes(3), 10, 150, 150, 3));
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(person("6.6.6." + i)).allowed()).isTrue();
        }

        Decision denied = limiter.tryAcquire(person("6.6.6.9")); // 새 IP: 닫는 쪽으로 실패
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
        assertThat(limiter.tryAcquire(person("6.6.6.1")).allowed()).as("이미 추적 중인 IP는 계속 이용").isTrue();

        clock.advance(Duration.ofSeconds(181)); // 기록이 만료되면 정리되어 새 IP도 받는다

        assertThat(limiter.tryAcquire(person("6.6.6.9")).allowed()).isTrue();
    }

    @Test
    void 동시에_호출해도_정확히_한도만큼만_통과한다() throws Exception {
        LlmRateLimiter limiter = limiter(10, 150, 150);
        ClientKey me = person("1.1.1.1");
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                go.await();
                if (limiter.tryAcquire(me).allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(allowed.get()).isEqualTo(10);
    }
}
