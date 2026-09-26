package com.smartcollab.global.security;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 인증·인가 설정.
 * <ul>
 *   <li>인증: JWT(HS256)를 HttpOnly 쿠키로 전달 — {@link CookieAuthenticationFilter} 가 Nimbus JwtDecoder 로 검증.
 *       Authorization 헤더 토큰은 OAuth2 Resource Server 가 처리.</li>
 *   <li>CSRF: 쿠키 인증이므로 SPA 방식 CSRF 토큰(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)을 요구.</li>
 *   <li>인가: 경로 단위는 "로그인 여부"까지만, 파일·팀 단위 권한은 서비스 계층의 AccessPolicy 가 판단.</li>
 *   <li>보안 헤더: CSP(외부 스크립트·eval 금지), Referrer-Policy, Permissions-Policy.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "frame-src 'self' blob: https://view.officeapps.live.com",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens, AuthCookies cookies,
                                            ObjectMapper mapper, AppProperties props) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(JwtTokenService.CLAIM_USERNAME);

        http
                // 세션 없이 요청마다 인증하므로, 기본 CSRF 인증 전략은 "새로 인증됨"으로 보고 요청마다 토큰을 교체합니다.
                // 그러면 요청이 겹칠 때 헤더와 쿠키가 어긋나 403 이 나므로 끄고, 교체는 로그인·로그아웃 때만 합니다 [BUG-08].
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/public/**")
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/signup", "/api/auth/logout", "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**", "/ws", "/ws/**").authenticated()
                        // 나머지는 SPA 정적 리소스와 화면 경로
                        .anyRequest().permitAll())
                // 브라우저: HttpOnly 쿠키 → CookieAuthenticationFilter (CSRF 검사 대상)
                .addFilterBefore(new CookieAuthenticationFilter(tokens.decoder(), converter, cookies),
                        BearerTokenAuthenticationFilter.class)
                // API 클라이언트·Swagger: Authorization: Bearer 헤더 → Resource Server (헤더 인증은 CSRF 대상 아님)
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(tokens.decoder()).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(response, mapper, ErrorCode.UNAUTHORIZED)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> writeProblem(response, mapper, ErrorCode.UNAUTHORIZED))
                        // 필터 단계의 접근 거부는 CSRF 토큰 문제뿐입니다 (경로 규칙은 로그인 여부만 봄).
                        .accessDeniedHandler((request, response, ex) -> writeProblem(response, mapper,
                                ex instanceof CsrfException ? ErrorCode.CSRF_INVALID : ErrorCode.FORBIDDEN)))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(f -> f.sameOrigin())
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=()")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtTokenService tokens) {
        return tokens.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins() == null ? List.of() : props.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Authorization"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        writeProblem(response, mapper, code, code.defaultMessage());
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper mapper, ErrorCode code, String message)
            throws IOException {
        ProblemDetail body = GlobalExceptionHandler.body(code, message);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
