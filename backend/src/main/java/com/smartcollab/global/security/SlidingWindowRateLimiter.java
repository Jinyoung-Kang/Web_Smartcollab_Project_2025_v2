package com.smartcollab.global.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 슬라이딩 윈도(로그) 방식의 인메모리 요청 제한기.
 * 키마다 최근 요청 시각을 보관하고, 창(window) 밖의 기록은 버린 뒤 남은 개수가 한도 미만일 때만 허용합니다.
 * 로그인·공유 링크 비밀번호의 무차별 대입을 막는 용도입니다.
 * <p>키 단위 원자성은 {@link ConcurrentHashMap#compute}(버킷 잠금)로 보장합니다.
 * 단일 인스턴스 전제이며, 여러 인스턴스로 확장할 때는 Redis 등 공유 저장소 기반으로 교체해야 합니다.</p>
 */
@Component
public class SlidingWindowRateLimiter {

    private static final Duration LONGEST_WINDOW = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
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
        long now = clock.millis();
        long windowStart = now - window.toMillis();
        boolean[] allowed = {false};
        hits.compute(key, (k, deque) -> {
            Deque<Long> d = deque == null ? new ArrayDeque<>() : deque;
            while (!d.isEmpty() && d.peekFirst() <= windowStart) {
                d.pollFirst();
            }
            if (d.size() < limit) {
                d.addLast(now);
                allowed[0] = true;
            }
            return d;
        });
        return allowed[0];
    }

    /** 성공한 로그인 등으로 기록을 초기화합니다. */
    public void reset(String key) {
        hits.remove(key);
    }

    /** 오래된 키를 정리해 메모리가 계속 늘지 않게 합니다. */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void evictStale() {
        long threshold = clock.millis() - LONGEST_WINDOW.toMillis();
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, d) -> d.isEmpty() || d.peekLast() <= threshold ? null : d);
        }
    }

    int trackedKeys() {
        return hits.size();
    }
}
