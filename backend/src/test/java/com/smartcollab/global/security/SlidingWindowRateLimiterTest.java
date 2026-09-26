package com.smartcollab.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("창 안에서는 한도까지만 허용하고, 창이 지나면 다시 허용한다")
    void slidesWindow() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isFalse();
        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("other", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    @DisplayName("동시 요청에서도 한도를 정확히 지킨다")
    void concurrent() throws Exception {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 1000; i++) {
            pool.submit(() -> {
                if (limiter.tryAcquire("burst", 100, Duration.ofMinutes(1))) allowed.incrementAndGet();
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    @DisplayName("오래된 키는 정리되어 메모리가 계속 늘지 않는다")
    void evictsStaleKeys() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        for (int i = 0; i < 100; i++) limiter.tryAcquire("ip" + i, 5, Duration.ofMinutes(1));
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        limiter.evictStale();
        assertThat(limiter.trackedKeys()).isZero();
    }
}
