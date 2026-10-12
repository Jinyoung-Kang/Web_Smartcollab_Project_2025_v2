package com.smartcollab.global.gate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 접근 코드 무차별 대입 제한 [GATE-01] — 다른 서비스 앞단(gate)과 같은 기준입니다.
 * <ul>
 *   <li>IP 하나가 15분 안에 5번 틀리면 그 IP 를 15분 잠급니다.</li>
 *   <li>모든 IP 를 합쳐 1시간 안에 30번 틀리면 30분 동안 누구도 시도할 수 없습니다(여러 IP 로 나눠 맞히는 것 방지).</li>
 * </ul>
 * 성공한 시도는 세지 않습니다. 인메모리라 인스턴스 1개를 전제로 합니다(다른 요청 제한과 같음).
 */
final class GateLimiter {

    static final Duration IP_WINDOW = Duration.ofMinutes(15);
    static final int IP_MAX_FAILS = 5;
    static final Duration IP_LOCK = Duration.ofMinutes(15);
    static final Duration GLOBAL_WINDOW = Duration.ofHours(1);
    static final int GLOBAL_MAX_FAILS = 30;
    static final Duration GLOBAL_LOCK = Duration.ofMinutes(30);
    static final int MAX_TRACKED_IPS = 10_000;

    private final Clock clock;
    private final Map<String, Deque<Long>> failsByIp = new HashMap<>();
    private final Map<String, Long> lockedIpUntil = new HashMap<>();
    private final Deque<Long> globalFails = new ArrayDeque<>();
    private long globalLockedUntil;

    GateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 잠겨 있으면 남은 시간, 아니면 0 */
    synchronized Duration blockedFor(String ip) {
        long now = clock.millis();
        long until = Math.max(globalLockedUntil, lockedIpUntil.getOrDefault(ip, 0L));
        return until > now ? Duration.ofMillis(until - now) : Duration.ZERO;
    }

    synchronized void recordFailure(String ip) {
        long now = clock.millis();
        if (failsByIp.size() >= MAX_TRACKED_IPS && !failsByIp.containsKey(ip)) {
            prune(now);
        }
        Deque<Long> fails = failsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        fails.addLast(now);
        expire(fails, now - IP_WINDOW.toMillis());
        if (fails.size() >= IP_MAX_FAILS) {
            lockedIpUntil.put(ip, now + IP_LOCK.toMillis());
            fails.clear();
        }
        globalFails.addLast(now);
        expire(globalFails, now - GLOBAL_WINDOW.toMillis());
        if (globalFails.size() >= GLOBAL_MAX_FAILS) {
            globalLockedUntil = now + GLOBAL_LOCK.toMillis();
            globalFails.clear();
        }
    }

    synchronized void recordSuccess(String ip) {
        failsByIp.remove(ip);
    }

    private static void expire(Deque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
    }

    private void prune(long now) {
        lockedIpUntil.values().removeIf(until -> until <= now);
        failsByIp.values().removeIf(q -> q.isEmpty() || q.peekLast() <= now - IP_WINDOW.toMillis());
        Iterator<String> oldest = failsByIp.keySet().iterator();
        while (failsByIp.size() >= MAX_TRACKED_IPS && oldest.hasNext()) {   // 그래도 넘치면 아무것이나 버림
            oldest.next();
            oldest.remove();
        }
    }
}
