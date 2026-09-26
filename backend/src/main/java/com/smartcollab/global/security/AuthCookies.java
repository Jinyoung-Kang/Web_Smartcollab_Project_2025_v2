package com.smartcollab.global.security;

import com.smartcollab.global.config.AppProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 인증 토큰을 담는 HttpOnly 쿠키. 자바스크립트에서 읽을 수 없으므로 XSS 로 토큰이 탈취되지 않습니다
 * (v1 은 localStorage 에 저장). SameSite=Strict 로 다른 사이트에서 시작된 요청에는 실리지 않습니다.
 */
@Component
public class AuthCookies {

    public static final String NAME = "SC_AUTH";

    private final boolean secure;

    public AuthCookies(AppProperties props) {
        this.secure = props.cookie().secure();
    }

    public ResponseCookie issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/");
    }
}
