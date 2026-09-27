package com.smartcollab.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * HttpOnly 쿠키(SC_AUTH)의 JWT 로 인증합니다.
 * <p>쿠키 토큰을 OAuth2 Resource Server 의 BearerTokenResolver 로 읽게 하면, Spring Security 가
 * "Bearer 토큰 요청은 CSRF 검사를 생략"하는 기본 동작 때문에 쿠키 요청의 CSRF 보호까지 꺼집니다(테스트로 확인).
 * 그래서 쿠키 인증은 이 필터가 따로 처리하고, Resource Server 는 Authorization 헤더(API 클라이언트)만 담당합니다.</p>
 */
public class CookieAuthenticationFilter extends OncePerRequestFilter {

    /** 인증된 사용자 ID. 요청 접근 기록(RequestTraceFilter)이 읽습니다. */
    public static final String USER_ID_ATTRIBUTE = CookieAuthenticationFilter.class.getName() + ".userId";

    private static final Set<String> PUBLIC_API = Set.of(
            "/api/auth/login", "/api/auth/signup", "/api/auth/logout", "/api/auth/csrf");

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;
    private final AuthCookies cookies;

    public CookieAuthenticationFilter(JwtDecoder decoder, JwtAuthenticationConverter converter, AuthCookies cookies) {
        this.decoder = decoder;
        this.converter = converter;
        this.cookies = cookies;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 공개 경로(로그인·공유 페이지·정적 리소스)에서는 만료된 쿠키가 남아 있어도 막히지 않도록 해석하지 않습니다.
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean protectedPath = (path.startsWith("/api/") && !path.startsWith("/api/public/") && !PUBLIC_API.contains(path))
                || path.equals("/ws") || path.startsWith("/ws/");
        return !protectedPath || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = readCookie(request);
        if (token != null) {
            try {
                Jwt jwt = decoder.decode(token);
                AbstractAuthenticationToken authentication = converter.convert(jwt);
                request.setAttribute(USER_ID_ATTRIBUTE, jwt.getSubject());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (JwtException e) {
                // 만료·위조된 쿠키는 지워 다음 요청부터 익명으로 처리되게 합니다. (보호된 경로라면 이후 401)
                SecurityContextHolder.clearContext();
                response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
            }
        }
        chain.doFilter(request, response);
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] all = request.getCookies();
        if (all == null) return null;
        for (Cookie c : all) {
            if (AuthCookies.NAME.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}
