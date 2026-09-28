package com.smartcollab.global.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 슬라이딩 윈도(로그) 방식의 인메모리 요청 제한기.
 * 키마다 최근 요청 시각을 보관하고, 창(window) 밖의 기록은 버린 뒤 남은 개수가 한도 미만일 때만 허용합니다.
 * 로그인·공유 링크 비밀번호의 무차별 대입을 막고, 무게를 매겨 번역처럼 양이 정해진 자원의 사용량도 제한합니다.
 * <p>키 단위 원자성은 {@link ConcurrentHashMap#compute}(버킷 잠금)로 보장합니다.
 * 단일 인스턴스 전제이며, 여러 인스턴스로 확장할 때는 Redis 등 공유 저장소 기반으로 교체해야 합니다.</p>
 */
@Component
public class SlidingWindowRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    /** 지금까지 쓰인 가장 긴 창. 정리할 때 이보다 최근 기록이 있는 키는 남깁니다 (고정값이면 긴 창의 기록이 일찍 지워짐). */
    private final AtomicLong longestWindowMillis = new AtomicLong(Duration.ofMinutes(10).toMillis());
    private final Clock clock;

    public SlidingWindowRateLimiter() {
        this(Clock.systemUTC());
    }

    SlidingWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return 허용되면 true. 허용된 요청만 기록합니다.
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        return tryAcquire(key, 1, limit, window);
    }

    /**
     * 요청마다 무게(예: 번역할 글자 수)를 매겨, 창 안의 무게 합계가 limit 을 넘지 않을 때만 허용합니다 [SEC-07].
     *
     * @return 허용되면 true. 허용된 요청만 기록합니다.
     */
    public boolean tryAcquire(String key, long weight, long limit, Duration window) {
        long now = clock.millis();
        long windowStart = now - window.toMillis();
        longestWindowMillis.accumulateAndGet(window.toMillis(), Math::max);
        boolean[] allowed = {false};
        windows.compute(key, (k, existing) -> {
            Window w = existing == null ? new Window() : existing;
            w.expireUpTo(windowStart);
            if (w.total + weight <= limit) {
                w.add(now, weight);
                allowed[0] = true;
            }
            return w;
        });
        return allowed[0];
    }

    /** 성공한 로그인 등으로 기록을 초기화합니다. */
    public void reset(String key) {
        windows.remove(key);
    }

    /** 오래된 키를 정리해 메모리가 계속 늘지 않게 합니다. */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void evictStale() {
        long threshold = clock.millis() - longestWindowMillis.get();
        for (String key : windows.keySet()) {
            windows.computeIfPresent(key, (k, w) -> w.newestAt() <= threshold ? null : w);
        }
    }

    int trackedKeys() {
        return windows.size();
    }

    /** 한 키의 창 안 기록(시각·무게)과 무게 합계. {@link ConcurrentHashMap#compute} 안에서만 다룹니다. */
    private static final class Window {
        private final Deque<long[]> entries = new ArrayDeque<>();
        private long total;

        void add(long at, long weight) {
            entries.addLast(new long[]{at, weight});
            total += weight;
        }

        void expireUpTo(long windowStart) {
            while (!entries.isEmpty() && entries.peekFirst()[0] <= windowStart) {
                total -= entries.pollFirst()[1];
            }
        }

        long newestAt() {
            return entries.isEmpty() ? Long.MIN_VALUE : entries.peekLast()[0];
        }
    }
}
