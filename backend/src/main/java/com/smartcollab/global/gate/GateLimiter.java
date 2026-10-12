package com.smartcollab.global.gate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 접근 코드 무차별 대입 제한 [GATE-01] — 다른 서비스 앞단(gate)과 같은 기준입니다.
 * <ul>
 *   <li>IP 하나가 15분 안에 5번 틀리면 그 IP 를 15분 잠급니다.</li>
 *   <li>모든 IP 를 합쳐 1시간 안에 30번 틀리면 30분 동안 누구도 시도할 수 없습니다(여러 IP 로 나눠 맞히는 것 방지).</li>
 * </ul>
 * 시도는 코드를 계산하기 <b>전에</b> 실패로 먼저 세고(맞으면 되돌림), IP 마다 진행 중인 시도는 1건만 받습니다 [M-1].
 * 예전에는 계산 뒤에 세어, 동시에 보낸 요청이 모두 잠금 확인을 통과해 IP 당 5회를 넘겨 계산했고, IP 하나로도 전체 잠금을 걸 수 있었습니다.
 * 인메모리라 인스턴스 1개를 전제로 합니다(다른 요청 제한과 같음).
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
    private final Set<String> inFlight = new HashSet<>();
    private long globalLockedUntil;

    GateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 시도 하나를 예약합니다. 잠겨 있거나 같은 IP 의 시도가 진행 중이면 거절하고, 아니면 실패로 먼저 세어 둡니다.
     * 예약을 받았으면 반드시 {@link #succeeded}·{@link #failed}·{@link #cancel} 중 하나로 끝내야 합니다.
     */
    synchronized Reservation reserve(String ip) {
        long now = clock.millis();
        long until = Math.max(globalLockedUntil, lockedIpUntil.getOrDefault(ip, 0L));
        if (until > now) {
            return Reservation.blocked(Duration.ofMillis(until - now));
        }
        if (!inFlight.add(ip)) {
            return Reservation.busy();
        }
        if (failsByIp.size() >= MAX_TRACKED_IPS && !failsByIp.containsKey(ip)) {
            prune(now);
        }
        Deque<Long> fails = failsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        expire(fails, now - IP_WINDOW.toMillis());
        fails.addLast(now);
        boolean lockedIp = fails.size() >= IP_MAX_FAILS;
        if (lockedIp) {
            lockedIpUntil.put(ip, now + IP_LOCK.toMillis());
        }
        expire(globalFails, now - GLOBAL_WINDOW.toMillis());
        globalFails.addLast(now);
        boolean lockedGlobal = globalFails.size() >= GLOBAL_MAX_FAILS;
        if (lockedGlobal) {
            globalLockedUntil = now + GLOBAL_LOCK.toMillis();
        }
        return new Reservation(ip, now, lockedIp, lockedGlobal, Duration.ZERO, false);
    }

    /** 맞는 코드 — 미리 센 실패와 그 시도로 건 잠금을 되돌리고, 그 IP 의 실패 기록을 지웁니다. */
    synchronized void succeeded(Reservation r) {
        inFlight.remove(r.ip());
        failsByIp.remove(r.ip());
        if (r.lockedIp()) {
            lockedIpUntil.remove(r.ip());
        }
        globalFails.removeLastOccurrence(r.at());
        if (r.lockedGlobal()) {
            globalLockedUntil = 0;
        }
    }

    /** 틀린 코드 — 미리 센 실패를 그대로 두고, 잠금 문턱에 닿았으면 기록을 비웁니다(잠금 시간 동안은 어차피 거절). */
    synchronized void failed(Reservation r) {
        inFlight.remove(r.ip());
        if (r.lockedIp()) {
            failsByIp.remove(r.ip());
        }
        if (r.lockedGlobal()) {
            globalFails.clear();
        }
    }

    /** 계산하지 못한 시도(서버가 바빠 거절) — 세지 않은 것으로 되돌립니다. */
    synchronized void cancel(Reservation r) {
        inFlight.remove(r.ip());
        Deque<Long> fails = failsByIp.get(r.ip());
        if (fails != null) {
            fails.removeLastOccurrence(r.at());
        }
        if (r.lockedIp()) {
            lockedIpUntil.remove(r.ip());
        }
        globalFails.removeLastOccurrence(r.at());
        if (r.lockedGlobal()) {
            globalLockedUntil = 0;
        }
    }

    private static void expire(Deque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
    }

    private void prune(long now) {
        lockedIpUntil.values().removeIf(until -> until <= now);
        failsByIp.entrySet().removeIf(e -> !inFlight.contains(e.getKey())
                && (e.getValue().isEmpty() || e.getValue().peekLast() <= now - IP_WINDOW.toMillis()));
        Iterator<String> oldest = failsByIp.keySet().iterator();
        while (failsByIp.size() >= MAX_TRACKED_IPS && oldest.hasNext()) {   // 그래도 넘치면 진행 중이 아닌 것부터 버림
            if (!inFlight.contains(oldest.next())) {
                oldest.remove();
            }
        }
    }

    /**
     * @param retryAfter 거절됐으면 남은 잠금 시간(진행 중인 시도 때문이면 0)
     */
    record Reservation(String ip, long at, boolean lockedIp, boolean lockedGlobal, Duration retryAfter, boolean rejected) {

        static Reservation blocked(Duration retryAfter) {
            return new Reservation(null, 0, false, false, retryAfter, true);
        }

        static Reservation busy() {
            return new Reservation(null, 0, false, false, Duration.ZERO, true);
        }
    }
}
