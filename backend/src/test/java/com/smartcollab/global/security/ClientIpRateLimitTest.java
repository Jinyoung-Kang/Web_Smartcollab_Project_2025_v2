package com.smartcollab.global.security;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 서버(랜덤 포트, Tomcat)로 요청해 요청 제한의 "클라이언트 IP" 판별을 검증합니다.
 * <p>[SEC-01] 이전 설정(forward-headers-strategy: framework)은 X-Forwarded-For 의 맨 앞 값(클라이언트가 마음대로 넣을 수 있음)을
 * IP 로 믿어, 헤더만 바꾸면 로그인·공유 비밀번호 시도 제한을 우회할 수 있었습니다.
 * 이 테스트는 프록시(127.0.0.1)가 실제 접속 IP 를 헤더 끝에 덧붙이는 상황을 흉내 냅니다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientIpRateLimitTest extends IntegrationTest {

    /** IntegrationTest 가 설정한 테스트용 로그인 한도(분당) */
    private static final int LIMIT = 1000;

    @LocalServerPort
    int port;

    @Autowired
    SlidingWindowRateLimiter rateLimiter;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("[SEC-01] X-Forwarded-For 맨 앞 값을 바꿔도 프록시가 덧붙인 실제 IP 기준으로 제한된다")
    void spoofedForwardedForDoesNotBypassLimit() throws Exception {
        String realIp = randomTestIp();
        exhaust("login:" + realIp);

        int status = login("203.0.113." + ThreadLocalRandom.current().nextInt(1, 255) + ", " + realIp);

        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("[SEC-01] 프록시가 IP 에 포트를 붙여 보내도(1.2.3.4:5678) 같은 IP 로 묶어 제한한다")
    void portSuffixIsIgnored() throws Exception {
        String realIp = randomTestIp();
        exhaust("login:" + realIp);

        int status = login(realIp + ":" + ThreadLocalRandom.current().nextInt(1024, 65535));

        assertThat(status).isEqualTo(429);
    }

    private void exhaust(String key) {
        while (rateLimiter.tryAcquire(key, LIMIT, Duration.ofMinutes(1))) {
            // 한도까지 채움
        }
    }

    /** CSRF 토큰을 받은 뒤, 없는 계정으로 로그인해 상태 코드를 돌려줍니다 (401 = 시도 허용, 429 = 제한). */
    private int login(String forwardedFor) throws Exception {
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String token = csrf.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
                .findFirst().orElseThrow();
        HttpRequest login = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"no_such_user_ip\",\"password\":\"wrong-pass-1\"}"))
                .build();
        return http.send(login, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /** 문서용 예약 대역(TEST-NET-2) 안의 임의 IP */
    private static String randomTestIp() {
        return "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
    }
}
