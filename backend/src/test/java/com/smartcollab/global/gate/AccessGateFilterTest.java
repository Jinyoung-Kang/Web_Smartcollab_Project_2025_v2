package com.smartcollab.global.gate;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [GATE-01] 접근 코드 잠금 필터 — 잠금화면·차단·잠금 해제·시도 제한·쿠키 위조/만료/폐기·열린 리디렉션.
 * 요청 하나하나를 직접 만들어 확인하고, 실제 서버에서의 순서(보안 필터보다 먼저)는 {@code AccessGateIntegrationTest} 가 확인합니다.
 */
class AccessGateFilterTest {

    private static final String SECRET = "gate-secret-gate-secret-gate-secret-1234";
    private static final String CODE = "OPEN-SESAME-42";
    private static final byte[] SALT = new byte[16];
    /** 시험 속도를 위해 N 을 낮춘 해시 (형식·확인 방식은 운영과 같음) */
    private static final String CODES = "recruit:" + AccessCodes.hash(CODE, SALT, 1024, 8, 1)
            + ",other:" + AccessCodes.hash("ANOTHER-CODE", SALT, 1024, 8, 1);
    private static final String HOST = "smartcollab.example";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
    private AccessGateFilter filter = filter(CODES);

    @Test
    @DisplayName("[GATE-01] 잠금을 켜지 않으면(GATE_CODES 없음) 모든 요청을 그대로 통과시킨다")
    void disabledWithoutCodes() throws Exception {
        filter = new AccessGateFilter(new GateProperties("", "", "", null), true, false, JsonMapper.builder().build(), clock);

        Result r = send(api("/api/auth/me"));

        assertThat(r.passed).isTrue();
    }

    @Test
    @DisplayName("[GATE-01] 잠금을 풀지 않은 API·WebSocket 요청은 앱에 닿기 전에 401 GATE_LOCKED 로 막는다")
    void locksApiAndWebSocket() throws Exception {
        Result apiCall = send(api("/api/auth/me"));
        MockHttpServletRequest ws = page("/ws");
        ws.addHeader("Upgrade", "websocket");
        Result upgrade = send(ws);

        assertThat(apiCall.passed).isFalse();
        assertThat(apiCall.res.getStatus()).isEqualTo(401);
        assertThat(apiCall.res.getContentType()).startsWith("application/problem+json");
        assertThat(apiCall.res.getContentAsString()).contains("GATE_LOCKED");
        assertThat(upgrade.passed).isFalse();
        assertThat(upgrade.res.getStatus()).isEqualTo(401);
        assertThat(upgrade.res.getContentAsString()).contains("GATE_LOCKED");
    }

    @Test
    @DisplayName("[GATE-01] 화면 이동은 잠금화면(401)을 보여 주고, 잠금화면에는 인라인 스크립트·스타일이 없다")
    void showsGatePageForNavigation() throws Exception {
        MockHttpServletRequest h2c = page("/");
        h2c.addHeader("Upgrade", "h2c");   // HTTP/2 업그레이드를 함께 보내는 클라이언트도 화면 이동으로 봄
        assertThat(send(h2c).res.getContentType()).startsWith("text/html");

        Result r = send(page("/drive/12?tab=files"));

        String html = r.res.getContentAsString();
        assertThat(r.passed).isFalse();
        assertThat(r.res.getStatus()).isEqualTo(401);
        assertThat(r.res.getContentType()).startsWith("text/html");
        assertThat(r.res.getHeader("Content-Security-Policy")).isEqualTo(AccessGateFilter.PAGE_CSP).doesNotContain("unsafe-inline");
        assertThat(r.res.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(html).contains("접근 코드가 필요합니다", "name=\"next\" value=\"/drive/12?tab=files\"",
                "href=\"/__gate/gate.css\"", "action=\"/__gate/unlock\"", "운영 시간: <span class=\"u\">상시</span>");
        assertThat(html).doesNotContain("<style", "<script", " style=");
    }

    @Test
    @DisplayName("[L-4] 상태 확인 경로처럼 보이지만 다른 곳을 가리키는 경로(.. · ; · 인코딩)는 잠금을 통과하지 못한다")
    void healthLookalikePathsStayLocked() throws Exception {
        for (String path : List.of("/actuator/health/../../api/auth/me", "/actuator/health/..;/..;/api/auth/me",
                "/actuator/health/%2e%2e/%2e%2e/api/auth/me", "/actuator/health;x=1/../../api/auth/me", "/actuator/health//api")) {
            Result r = send(api(path));
            assertThat(r.passed).as(path).isFalse();
            assertThat(r.res.getStatus()).as(path).isEqualTo(401);
        }
        assertThat(send(api("/actuator/health")).passed).isTrue();
        assertThat(send(api("/actuator/health/liveness")).passed).isTrue();
    }

    @Test
    @DisplayName("[GATE-01] 상태 확인(health)과 잠금화면 스타일은 잠금 없이 열린다")
    void healthAndStylesheetAreOpen() throws Exception {
        Result health = send(api("/actuator/health/readiness"));
        Result css = send(api("/__gate/gate.css"));

        assertThat(health.passed).isTrue();
        assertThat(css.passed).isFalse();
        assertThat(css.res.getStatus()).isEqualTo(200);
        assertThat(css.res.getContentType()).startsWith("text/css");
        assertThat(css.res.getContentAsString()).contains("--accent");
    }

    @Test
    @DisplayName("[GATE-01] 맞는 코드면 서명 쿠키(__Host-, HttpOnly, Secure, SameSite=Lax)를 주고 원래 주소로 보낸다 — 그 뒤 요청은 통과")
    void unlocksWithCorrectCode() throws Exception {
        Result r = send(unlock(CODE, "/drive/12?tab=files", "203.0.113.5"));

        String setCookie = r.res.getHeader("Set-Cookie");
        assertThat(r.res.getStatus()).isEqualTo(303);
        assertThat(r.res.getHeader("Location")).isEqualTo("/drive/12?tab=files");
        assertThat(setCookie).startsWith(AccessGateFilter.SECURE_COOKIE + "=")
                .contains("Path=/", "Max-Age=43200", "HttpOnly", "Secure", "SameSite=Lax").doesNotContain("Domain");
        assertThat(send(withCookie(api("/api/auth/me"), cookieValue(setCookie))).passed).isTrue();
        assertThat(send(withCookie(page("/"), cookieValue(setCookie))).passed).isTrue();
    }

    @Test
    @DisplayName("[GATE-01] 틀린 코드는 잠금화면에 오류를 보여 주고(401) 쿠키를 주지 않는다")
    void rejectsWrongCode() throws Exception {
        Result r = send(unlock("WRONG", "/", "203.0.113.5"));

        assertThat(r.res.getStatus()).isEqualTo(401);
        assertThat(r.res.getHeader("Set-Cookie")).isNull();
        assertThat(r.res.getContentAsString()).contains("접근 코드가 올바르지 않습니다.", "role=\"alert\"", "aria-invalid=\"true\"");
    }

    @Test
    @DisplayName("[GATE-01] 한 IP 가 15분 안에 5번 틀리면 15분 동안 맞는 코드도 받지 않는다(429, Retry-After)")
    void locksOutIpAfterFiveFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send(unlock("WRONG-" + i, "/", "203.0.113.5")).res.getStatus()).isEqualTo(401);
        }

        Result blocked = send(unlock(CODE, "/", "203.0.113.5"));
        Result otherIp = send(unlock(CODE, "/", "198.51.100.7"));
        clock.advance(GateLimiter.IP_LOCK.plusSeconds(1));
        Result later = send(unlock(CODE, "/", "203.0.113.5"));

        assertThat(blocked.res.getStatus()).isEqualTo(429);
        assertThat(blocked.res.getHeader("Retry-After")).isNotNull();
        assertThat(blocked.res.getContentAsString()).contains("시도가 너무 많습니다");
        assertThat(otherIp.res.getStatus()).isEqualTo(303);
        assertThat(later.res.getStatus()).isEqualTo(303);
    }

    @Test
    @DisplayName("[GATE-01] 여러 IP 로 나눠 시도해도 1시간에 30번 틀리면 30분 동안 모두 잠근다")
    void locksEveryoneAfterThirtyFailures() throws Exception {
        for (int i = 0; i < GateLimiter.GLOBAL_MAX_FAILS; i++) {
            send(unlock("WRONG", "/", "10.0." + (i / 200) + "." + (i % 200)));
        }

        assertThat(send(unlock(CODE, "/", "192.0.2.99")).res.getStatus()).isEqualTo(429);
        clock.advance(GateLimiter.GLOBAL_LOCK.plusSeconds(1));
        assertThat(send(unlock(CODE, "/", "192.0.2.99")).res.getStatus()).isEqualTo(303);
    }

    @Test
    @DisplayName("[M-1] 같은 IP 가 동시에 보낸 시도도 15분 5회를 넘겨 계산하지 않는다 — 계산 전에 시도를 세고, IP 당 진행 중 1건")
    void concurrentAttemptsFromOneIpAreCounted() throws Exception {
        filter = filter("slow:" + AccessCodes.hash(CODE, SALT, 16384, 8, 1));   // 운영과 같은 비용(회당 수십 ms)이라 요청이 겹침

        List<Integer> statuses = sendConcurrently(20, i -> unlock("WRONG-" + i, "/", "203.0.113.5"));

        assertThat(statuses.stream().filter(s -> s == 401).count()).as("계산한 시도(401)").isLessThanOrEqualTo(GateLimiter.IP_MAX_FAILS);
        assertThat(statuses).allMatch(s -> s == 401 || s == 429);
    }

    @Test
    @DisplayName("[M-1] IP 하나가 동시에 30번 틀려도 전체 잠금이 걸리지 않는다 — 다른 IP 의 맞는 코드는 통과")
    void oneIpCannotTriggerGlobalLock() throws Exception {
        filter = filter("slow:" + AccessCodes.hash(CODE, SALT, 16384, 8, 1));

        sendConcurrently(GateLimiter.GLOBAL_MAX_FAILS, i -> unlock("WRONG-" + i, "/", "203.0.113.5"));

        assertThat(send(unlock(CODE, "/", "198.51.100.7")).res.getStatus()).isEqualTo(303);
    }

    @Test
    @DisplayName("[GATE-01] 다른 사이트에서 보낸 잠금 해제는 403 — Origin 이 null 이면 Sec-Fetch-Site 가 same-origin 일 때만 받는다")
    void rejectsCrossOriginUnlock() throws Exception {
        MockHttpServletRequest evil = unlock(CODE, "/", "203.0.113.5");
        evil.addHeader("Origin", "https://evil.example");
        MockHttpServletRequest sandboxed = unlock(CODE, "/", "203.0.113.5");
        sandboxed.addHeader("Origin", "null");
        sandboxed.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletRequest strictReferrer = unlock(CODE, "/", "203.0.113.5");
        strictReferrer.addHeader("Origin", "null");
        strictReferrer.addHeader("Sec-Fetch-Site", "same-origin");
        MockHttpServletRequest sameSite = unlock(CODE, "/", "203.0.113.5");
        sameSite.addHeader("Origin", "https://" + HOST);

        assertThat(send(evil).res.getStatus()).isEqualTo(403);
        assertThat(send(sandboxed).res.getStatus()).isEqualTo(403);
        assertThat(send(strictReferrer).res.getStatus()).isEqualTo(303);
        assertThat(send(sameSite).res.getStatus()).isEqualTo(303);
    }

    @Test
    @DisplayName("[GATE-01] 잠금 해제 뒤 이동할 주소는 같은 사이트 경로만 — 다른 사이트로 보내지 않는다")
    void preventsOpenRedirect() throws Exception {
        for (String next : new String[]{"//evil.example/x", "https://evil.example", "/\\evil.example", "/__gate/lock", "x", ""}) {
            assertThat(send(unlock(CODE, next, "203.0.113.5")).res.getHeader("Location")).as(next).isEqualTo("/");
        }
        assertThat(AccessGateFilter.safeNext("/s/abc?x=1")).isEqualTo("/s/abc?x=1");
        assertThat(AccessGateFilter.safeNext("/a\r\nSet-Cookie: x=1")).isEqualTo("/");
    }

    @Test
    @DisplayName("[GATE-01] 위조·만료된 쿠키, 그리고 목록에서 지운 코드로 받은 쿠키는 받지 않는다")
    void rejectsForgedExpiredAndRevokedCookies() throws Exception {
        String token = cookieValue(send(unlock(CODE, "/", "203.0.113.5")).res.getHeader("Set-Cookie"));
        String forged = new GateToken("another-secret-another-secret-123456".getBytes(StandardCharsets.UTF_8))
                .sign("recruit", clock.instant().plus(Duration.ofDays(1)).getEpochSecond());

        assertThat(send(withCookie(api("/api/x"), forged)).passed).isFalse();
        assertThat(send(withCookie(api("/api/x"), token.substring(0, token.length() - 2) + "xx")).passed).isFalse();
        assertThat(send(withCookie(api("/api/x"), "garbage")).passed).isFalse();

        filter = filter("other:" + AccessCodes.hash("ANOTHER-CODE", SALT, 1024, 8, 1));   // recruit 코드 폐기
        assertThat(send(withCookie(api("/api/x"), token)).passed).isFalse();

        filter = filter(CODES);
        assertThat(send(withCookie(api("/api/x"), token)).passed).isTrue();
        clock.advance(Duration.ofHours(12).plusSeconds(1));
        assertThat(send(withCookie(api("/api/x"), token)).passed).isFalse();
    }

    @Test
    @DisplayName("[GATE-01] 잠금 해제 요청 본문은 2KB 까지 — 크거나 길이를 알리지 않으면 계산 전에 413")
    void limitsUnlockBody() throws Exception {
        MockHttpServletRequest large = unlock(CODE, "/", "203.0.113.5");
        large.setContent(new byte[AccessGateFilter.MAX_FORM_BYTES + 1]);
        MockHttpServletRequest unknownLength = unlock(CODE, "/", "203.0.113.5");
        unknownLength.setContent(null);

        assertThat(send(large).res.getStatus()).isEqualTo(413);
        assertThat(send(unknownLength).res.getStatus()).isEqualTo(413);
    }

    @Test
    @DisplayName("[GATE-01] 잠금 해제 상태에서 잠금화면 주소는 첫 화면으로, /__gate/lock 은 쿠키를 지운다")
    void gatePathsAfterUnlock() throws Exception {
        String token = cookieValue(send(unlock(CODE, "/", "203.0.113.5")).res.getHeader("Set-Cookie"));

        Result gate = send(withCookie(page("/__gate/"), token));
        Result lock = send(withCookie(page("/__gate/lock"), token));
        Result unknown = send(api("/__gate/unknown"));

        assertThat(gate.res.getStatus()).isEqualTo(303);
        assertThat(gate.res.getHeader("Location")).isEqualTo("/");
        assertThat(lock.res.getStatus()).isEqualTo(303);
        assertThat(lock.res.getHeader("Location")).isEqualTo("/__gate/");
        assertThat(lock.res.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(unknown.res.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("[GATE-01] 잠금을 켰는데 서명 키가 짧거나 코드 형식이 틀리면 기동을 멈춘다")
    void failsFastOnBadConfiguration() {
        assertThatThrownBy(() -> new AccessGateFilter(new GateProperties(CODES, "short", "", null), true, false,
                JsonMapper.builder().build(), clock)).hasMessageContaining("GATE_SECRET");
        assertThatThrownBy(() -> new AccessGateFilter(new GateProperties("recruit", SECRET, "", null), true, false,
                JsonMapper.builder().build(), clock)).hasMessageContaining("GATE_CODES");
    }

    @Test
    @DisplayName("[GATE-01] 체험 계정을 켠 배포는 잠금화면에 체험 계정 안내를 덧붙이고, 운영 시간이 없으면 그 줄을 감춘다")
    void pageTextFollowsSettings() throws Exception {
        filter = new AccessGateFilter(new GateProperties(CODES, SECRET, "", null), true, true, JsonMapper.builder().build(), clock);

        String html = send(page("/")).res.getContentAsString();

        assertThat(html).contains("체험 계정").doesNotContain("운영 시간");
    }

    // ── 도우미 ──

    private AccessGateFilter filter(String codes) {
        return new AccessGateFilter(new GateProperties(codes, SECRET, "상시", null), true, false, JsonMapper.builder().build(), clock);
    }

    private Result send(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return new Result(res, chain.getRequest() != null);
    }

    /** 같은 순간에 출발시켜 상태 코드를 모읍니다 */
    private List<Integer> sendConcurrently(int n, java.util.function.IntFunction<MockHttpServletRequest> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                MockHttpServletRequest req = request.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return send(req).res.getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private static MockHttpServletRequest api(String path) {
        MockHttpServletRequest req = base("GET", path);
        req.addHeader("Accept", "application/json");
        return req;
    }

    private static MockHttpServletRequest page(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        MockHttpServletRequest req = base("GET", q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
        if (q >= 0) {
            req.setQueryString(pathAndQuery.substring(q + 1));
        }
        req.addHeader("Accept", "text/html,application/xhtml+xml");
        return req;
    }

    private static MockHttpServletRequest unlock(String code, String next, String ip) {
        MockHttpServletRequest req = base("POST", "/__gate/unlock");
        req.setRemoteAddr(ip);
        req.setContentType("application/x-www-form-urlencoded");
        req.addParameter("code", code);
        req.addParameter("next", next);
        req.setContent(("code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&next="
                + URLEncoder.encode(next, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return req;
    }

    private static MockHttpServletRequest base(String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setSecure(true);
        req.setServerName(HOST);
        req.addHeader("Host", HOST);
        return req;
    }

    private static MockHttpServletRequest withCookie(MockHttpServletRequest req, String value) {
        req.setCookies(new Cookie(AccessGateFilter.SECURE_COOKIE, value));
        return req;
    }

    private static String cookieValue(String setCookie) {
        String first = setCookie.split(";", 2)[0];
        return first.substring(first.indexOf('=') + 1);
    }

    private record Result(MockHttpServletResponse res, boolean passed) {
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

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
}
