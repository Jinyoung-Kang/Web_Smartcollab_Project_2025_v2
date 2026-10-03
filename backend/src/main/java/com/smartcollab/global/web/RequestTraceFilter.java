package com.smartcollab.global.web;

import com.smartcollab.global.error.GlobalExceptionHandler;
import com.smartcollab.global.security.ClientIp;
import com.smartcollab.global.security.CookieAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 추적 [ARC-02·SEC-11].
 * <ul>
 *   <li>요청마다 추적 ID 를 정해 로그(MDC)·응답 헤더({@value #HEADER})·오류 본문에 남깁니다. 사용자가 오류를 알려 오면
 *       이 ID 로 서버 로그를 바로 찾을 수 있습니다. 앞단 프록시가 준 ID 는 형식이 맞을 때만 이어 씁니다(로그 위조 방지).</li>
 *   <li>API·WebSocket 요청은 한 줄 접근 기록(메서드·경로·상태·소요 시간·사용자·IP)을 남깁니다. 쿼리 문자열은 공유 다운로드
 *       허가처럼 비밀값이 들어갈 수 있어 남기지 않습니다. 정적 파일과 상태 확인(health) 요청은 기록하지 않습니다.</li>
 * </ul>
 */
@Slf4j
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = GlobalExceptionHandler.REQUEST_ID;

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Pattern SHARE_TOKEN = Pattern.compile("^(/api/public/shares/)[^/]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && VALID_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (isTraced(path)) {
                path = maskSecrets(path);
                Object userId = request.getAttribute(CookieAuthenticationFilter.USER_ID_ATTRIBUTE);
                log.info("{} {} {} {}ms user={} ip={}", request.getMethod(), path, response.getStatus(),
                        (System.nanoTime() - started) / 1_000_000, userId == null ? "-" : userId, ClientIp.of(request));
            }
            MDC.remove(MDC_KEY);
        }
    }

    /** 공유 링크 토큰은 그 자체로 접근 권한(비밀번호 없는 링크라면 누구나 내려받음)이라 접근 기록에 남기지 않습니다 [S-07]. */
    static String maskSecrets(String path) {
        return SHARE_TOKEN.matcher(path).replaceFirst("$1***");
    }

    private static boolean isTraced(String path) {
        return path.startsWith("/api/") || path.equals("/ws") || path.startsWith("/ws/");
    }
}
