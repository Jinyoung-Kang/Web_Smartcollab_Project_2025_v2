package com.smartcollab.global.gate;

import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.error.ProblemWriter;
import com.smartcollab.global.security.ClientIp;
import com.smartcollab.global.security.SecurityEventLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 접근 코드 잠금 [GATE-01, ADR-0013]. 포트폴리오 데모를 코드를 받은 사람에게만 열어, 모르는 방문자가 가입·업로드·AI 요약으로
 * 서버 자원과 요금을 쓰지 못하게 합니다. 로그인(계정)과는 별개인 앞단 한 겹입니다.
 * <ul>
 *   <li>코드를 맞히면 서명 쿠키(기본 12시간)를 주고, 쿠키가 없거나 틀린 요청은 앱에 닿기 전에 막습니다.
 *       화면 이동(HTML)은 잠금화면, 나머지(API·WebSocket·정적 파일)는 401 problem+json {@code GATE_LOCKED} 입니다.</li>
 *   <li>상태 확인({@code /actuator/health/**})은 막지 않습니다 — App Service 헬스 체크가 쿠키 없이 부릅니다.</li>
 *   <li>틀린 시도는 IP·전체 단위로 제한하고({@link GateLimiter}), 해시 계산(회당 16MB)은 동시에 2개까지만 합니다.</li>
 * </ul>
 * 스프링 시큐리티보다 먼저 실행되므로 로그인·CSRF 처리와 섞이지 않습니다. 잠금 해제 요청의 위조는 Origin 확인으로 막습니다.
 */
public class AccessGateFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/__gate";
    /**
     * 잠금 없이 여는 상태 확인 경로 — 이름 조각만 허용합니다 [L-4]. 요청 URI 는 정규화 전 값이라, 앞부분만 비교하면
     * {@code /actuator/health/../../api/...} 처럼 다른 곳을 가리키는 경로도 통과했습니다(뒤의 보안 방화벽이 막았지만 한 겹에 기대지 않음).
     */
    static final Pattern HEALTH = Pattern.compile("/actuator/health(/[A-Za-z0-9-]+)*");
    static final int MAX_FORM_BYTES = 2048;
    static final int MAX_NEXT_CHARS = 2000;
    static final String PAGE_CSP = "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";
    static final String SECURE_COOKIE = "__Host-sc_gate";
    static final String PLAIN_COOKIE = "sc_gate";
    private static final int MIN_SECRET_BYTES = 32;

    private final boolean enabled;
    private final AccessCodes codes;
    private final GateToken tokens;
    private final GateLimiter limiter;
    private final GatePage page;
    private final Duration cookieTtl;
    private final boolean secureCookie;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Semaphore hashing = new Semaphore(2);
    private final byte[] css;

    public AccessGateFilter(GateProperties props, boolean secureCookie, boolean demoEnabled, ObjectMapper mapper, Clock clock) {
        this.enabled = props.enabled();
        this.secureCookie = secureCookie;
        this.cookieTtl = props.cookieTtl();
        this.mapper = mapper;
        this.clock = clock;
        this.limiter = new GateLimiter(clock);
        this.page = new GatePage(props.hours(), demoEnabled);
        if (!enabled) {
            this.codes = null;
            this.tokens = null;
            this.css = new byte[0];
            return;
        }
        this.codes = AccessCodes.parse(props.codes());
        byte[] secret = props.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("접근 코드 잠금(GATE_CODES)을 쓰려면 GATE_SECRET 을 32바이트 이상으로 설정하세요.");
        }
        if (cookieTtl.isNegative() || cookieTtl.isZero()) {
            throw new IllegalStateException("GATE_COOKIE_TTL 은 0 보다 커야 합니다.");
        }
        this.tokens = new GateToken(secret);
        this.css = readCss();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            handleGate(path, request, response);
            return;
        }
        if (HEALTH.matcher(path).matches() || authorizedAs(request).isPresent()) {
            chain.doFilter(request, response);
            return;
        }
        if (wantsHtml(request)) {
            String query = request.getQueryString();
            sendPage(request, response, HttpServletResponse.SC_UNAUTHORIZED, safeNext(query == null ? path : path + "?" + query), null);
            return;
        }
        commonHeaders(request, response);
        ProblemWriter.write(response, mapper, ErrorCode.GATE_LOCKED);
    }

    private void handleGate(String path, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (read && path.equals(PREFIX + "/gate.css")) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/css;charset=UTF-8");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLength(css.length);
            if (!"HEAD".equals(method)) {
                response.getOutputStream().write(css);
            }
        } else if (read && (path.equals(PREFIX) || path.equals(PREFIX + "/"))) {
            if (authorizedAs(request).isPresent()) {
                redirect(request, response, "/", null);
            } else {
                sendPage(request, response, HttpServletResponse.SC_OK, safeNext(request.getParameter("next")), null);
            }
        } else if (read && path.equals(PREFIX + "/lock")) {
            redirect(request, response, PREFIX + "/", cookie("", Duration.ZERO));
        } else if ("POST".equals(method) && path.equals(PREFIX + "/unlock")) {
            unlock(request, response);
        } else {
            commonHeaders(request, response);
            ProblemWriter.write(response, mapper, ErrorCode.NOT_FOUND);
        }
    }

    private void unlock(HttpServletRequest request, HttpServletResponse response) throws IOException {
        commonHeaders(request, response);
        if (!sameOrigin(request)) {
            ProblemWriter.write(response, mapper, ErrorCode.FORBIDDEN, "다른 사이트에서 보낸 잠금 해제 요청은 받지 않습니다.");
            return;
        }
        long length = request.getContentLengthLong();
        if (length < 0 || length > MAX_FORM_BYTES) {
            ProblemWriter.write(response, mapper, ErrorCode.PAYLOAD_TOO_LARGE, "잠금 해제 요청이 너무 큽니다.");
            return;
        }
        String next = safeNext(request.getParameter("next"));
        String code = Optional.ofNullable(request.getParameter("code")).orElse("");
        String ip = ClientIp.of(request);

        GateLimiter.Reservation attempt = limiter.reserve(ip);   // 계산 전에 시도를 세고, IP 당 진행 중 1건 [M-1]
        if (attempt.rejected()) {
            SecurityEventLog.rateLimited("gate-unlock", ip);
            Duration wait = attempt.retryAfter();
            if (wait.isZero()) {   // 같은 IP 의 앞선 시도를 확인하는 중
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                sendPage(request, response, 429, next, "앞선 시도를 확인하는 중입니다. 잠시 후 다시 시도하세요.");
            } else {
                long minutes = Math.max(1, Math.round(wait.toSeconds() / 60.0));
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(wait.toSeconds() + 1));
                sendPage(request, response, 429, next, "시도가 너무 많습니다. 약 " + minutes + "분 뒤에 다시 시도하세요.");
            }
            return;
        }
        Optional<String> matched;
        try {
            if (!hashing.tryAcquire(5, TimeUnit.SECONDS)) {
                limiter.cancel(attempt);
                sendPage(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, next, "잠시 후 다시 시도하세요.");
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            limiter.cancel(attempt);
            sendPage(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, next, "잠시 후 다시 시도하세요.");
            return;
        }
        try {
            matched = codes.match(code);
        } catch (RuntimeException e) {
            limiter.cancel(attempt);
            throw e;
        } finally {
            hashing.release();
        }
        if (matched.isEmpty()) {
            limiter.failed(attempt);
            SecurityEventLog.gateUnlockFailed(ip);
            sendPage(request, response, HttpServletResponse.SC_UNAUTHORIZED, next, "접근 코드가 올바르지 않습니다.");
            return;
        }
        limiter.succeeded(attempt);
        SecurityEventLog.gateUnlocked(matched.get(), ip);
        String token = tokens.sign(matched.get(), clock.instant().plus(cookieTtl).getEpochSecond());
        redirect(request, response, next, cookie(token, cookieTtl));
    }

    /** 쿠키가 올바르고, 그 쿠키를 받은 코드가 아직 목록에 있으면 코드 이름 */
    private Optional<String> authorizedAs(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = cookieName();
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return tokens.read(c.getValue(), clock.instant().getEpochSecond()).filter(codes::contains);
            }
        }
        return Optional.empty();
    }

    /** 화면 이동 — WebSocket 연결은 제외합니다(HTTP/2 업그레이드 h2c 를 같이 보내는 클라이언트도 있어 Upgrade 값까지 봅니다). */
    private static boolean wantsHtml(HttpServletRequest request) {
        String method = request.getMethod();
        String upgrade = request.getHeader(HttpHeaders.UPGRADE);
        boolean webSocket = upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
        if (!("GET".equals(method) || "HEAD".equals(method)) || webSocket) {
            return false;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }

    /**
     * Origin 이 있으면 Host 와 같아야 합니다(다른 사이트가 방문자 브라우저로 잠금 해제를 보내는 것 방지).
     * Origin 이 "null" 이면(엄격한 Referrer-Policy 의 폼 전송) 페이지가 바꿀 수 없는 Sec-Fetch-Site 가 same-origin 일 때만 받습니다.
     */
    static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) {
            return true;
        }
        if ("null".equals(origin)) {
            return "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
        }
        String host = request.getHeader(HttpHeaders.HOST);
        try {
            String authority = URI.create(origin).getRawAuthority();
            return authority != null && host != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 같은 사이트 안의 경로만 — 열린 리디렉션 방지 */
    static String safeNext(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_NEXT_CHARS) {
            return "/";
        }
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\") || raw.startsWith(PREFIX)) {
            return "/";
        }
        for (char c : raw.toCharArray()) {
            if (c == '\r' || c == '\n' || c == '\t' || c == '\\') {
                return "/";
            }
        }
        return raw;
    }

    private void sendPage(HttpServletRequest request, HttpServletResponse response, int status, String next, String error)
            throws IOException {
        commonHeaders(request, response);
        byte[] body = page.render(next, error).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Security-Policy", PAGE_CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setContentLength(body.length);
        if (!"HEAD".equals(request.getMethod())) {
            response.getOutputStream().write(body);
        }
    }

    private void redirect(HttpServletRequest request, HttpServletResponse response, String location, ResponseCookie cookie) {
        commonHeaders(request, response);
        if (cookie != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader(HttpHeaders.LOCATION, location);
    }

    private static void commonHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // no-referrer 면 브라우저가 폼 POST 의 Origin 을 "null" 로 보내 잠금 해제가 막힙니다 — 같은 출처에만 보냅니다
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains");
        }
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        // __Host- 접두사: Secure·Path=/·Domain 없음이 강제되어 하위 도메인이 덮어쓸 수 없습니다(HTTPS 에서만)
        return ResponseCookie.from(cookieName(), value)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    private String cookieName() {
        return secureCookie ? SECURE_COOKIE : PLAIN_COOKIE;
    }

    private static byte[] readCss() {
        try (InputStream in = new ClassPathResource("gate/gate.css").getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("잠금화면 스타일(gate/gate.css)을 읽을 수 없습니다", e);
        }
    }
}
