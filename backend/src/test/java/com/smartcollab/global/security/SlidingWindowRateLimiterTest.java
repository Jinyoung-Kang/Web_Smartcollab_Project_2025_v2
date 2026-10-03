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
    @DisplayName("[S-02] 키마다 자기 창이 지나면 정리된다 — 24시간 창이 한 번 쓰였다고 10분 창의 키까지 24시간 붙잡지 않는다")
    void evictsEachKeyByItsOwnWindow() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        limiter.tryAcquire("login-account:short", 10, Duration.ofMinutes(10));
        limiter.tryAcquire("translation:long", 1, 100, Duration.ofDays(1));
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        limiter.evictStale();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofDays(1));
        limiter.evictStale();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    @DisplayName("[SEC-07] 요청마다 무게(글자 수 등)를 매겨, 창 안의 합계가 한도를 넘지 않을 때만 허용한다")
    void weightedBudget() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        Duration day = Duration.ofDays(1);
        assertThat(limiter.tryAcquire("t", 60, 100, day)).isTrue();
        assertThat(limiter.tryAcquire("t", 50, 100, day)).isFalse();   // 합계 110: 거절하고 기록하지 않음
        assertThat(limiter.tryAcquire("t", 40, 100, day)).isTrue();    // 합계 100
        assertThat(limiter.tryAcquire("t", 1, 100, day)).isFalse();
        assertThat(limiter.tryAcquire("other", 100, 100, day)).isTrue();
        clock.now = clock.now.plus(day).plusSeconds(1);
        assertThat(limiter.tryAcquire("t", 100, 100, day)).isTrue();
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

    @Test
    @DisplayName("[SEC-05] 10분보다 긴 창(가입 제한 1시간)의 기록은 창이 끝나기 전에 정리되지 않는다")
    void longWindowsSurviveEviction() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock);
        for (int i = 0; i < 5; i++) limiter.tryAcquire("signup:1.2.3.4", 5, Duration.ofHours(1));
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        limiter.evictStale();

        assertThat(limiter.tryAcquire("signup:1.2.3.4", 5, Duration.ofHours(1))).isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(limiter.tryAcquire("signup:1.2.3.4", 5, Duration.ofHours(1))).isTrue();
    }
}
