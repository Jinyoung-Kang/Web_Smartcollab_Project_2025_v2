package com.smartcollab.global.gate;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [GATE-01] 실제 서버(Tomcat)에서 잠금이 스프링 시큐리티보다 먼저 동작하는지 — 로그인 검사(401 UNAUTHORIZED)·CSRF 보다 앞에서
 * GATE_LOCKED 로 막고, 잠금을 푼 쿠키가 있으면 평소대로 앱이 처리합니다. 상태 확인은 쿠키 없이 열립니다(App Service 헬스 체크).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.gate.codes=recruit:scrypt.1024.8.1.AAECAwQFBgcICQoLDA0ODw.tJfemhDdc34EcadjwLQcBOClGB5h5hiFKOwe0EO_ixQ",
        "app.gate.secret=gate-secret-gate-secret-gate-secret-1234",
        "app.gate.hours=상시"
})
class AccessGateIntegrationTest extends IntegrationTest {

    /** 위 해시의 원문 (ScryptTest 와 같은 Python 생성 값) */
    private static final String CODE = "접근코드-한글";

    @LocalServerPort
    int port;

    @Autowired
    GateNotifier notifier;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    @DisplayName("[GATE-02] 개발 PC 셸에 TELEGRAM_BOT_TOKEN·TELEGRAM_CHAT_ID 가 있어도 시험의 잠금 해제·차단은 실제 텔레그램으로 알리지 않는다")
    void alertsStayOffInTests() {
        assertThat(notifier.enabled()).isFalse();
    }

    @Test
    @DisplayName("[GATE-01] 잠금을 풀지 않은 API 는 로그인 검사보다 먼저 401 GATE_LOCKED(problem+json, requestId) 로 막는다")
    void locksBeforeSecurity() throws Exception {
        HttpResponse<String> me = http.send(get("/api/auth/me", "application/json", null), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> config = http.send(get("/api/public/config", "application/json", null), HttpResponse.BodyHandlers.ofString());

        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/problem+json"));
        assertThat(me.body()).contains("\"code\":\"GATE_LOCKED\"", "\"requestId\"");
        assertThat(config.statusCode()).isEqualTo(401);   // 공개 설정(체험 계정 비밀번호 안내)도 잠금 뒤
    }

    @Test
    @DisplayName("[GATE-01] 화면 이동은 잠금화면, 상태 확인(readiness)은 잠금 없이 200")
    void pageAndHealth() throws Exception {
        HttpResponse<String> home = http.send(get("/", "text/html", null), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> health = http.send(get("/actuator/health/readiness", "application/json", null),
                HttpResponse.BodyHandlers.ofString());

        assertThat(home.statusCode()).isEqualTo(401);
        assertThat(home.body()).contains("접근 코드가 필요합니다");
        assertThat(home.headers().firstValue("Content-Security-Policy")).contains(AccessGateFilter.PAGE_CSP);
        assertThat(health.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("[GATE-01] 잠금 해제 폼(같은 출처) → 쿠키 → 그 뒤 요청은 앱이 평소대로 처리한다")
    void unlockThenUseApp() throws Exception {
        String form = "code=" + java.net.URLEncoder.encode(CODE, java.nio.charset.StandardCharsets.UTF_8) + "&next=%2Fdrive";
        HttpResponse<String> unlock = http.send(HttpRequest.newBuilder(uri("/__gate/unlock"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "http://localhost:" + port)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

        String setCookie = unlock.headers().firstValue("Set-Cookie").orElseThrow();
        String cookie = setCookie.split(";", 2)[0];
        HttpResponse<String> me = http.send(get("/api/auth/me", "application/json", cookie), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> config = http.send(get("/api/public/config", "application/json", cookie), HttpResponse.BodyHandlers.ofString());

        assertThat(unlock.statusCode()).isEqualTo(303);
        assertThat(unlock.headers().firstValue("Location")).contains("/drive");
        assertThat(cookie).startsWith(AccessGateFilter.PLAIN_COOKIE + "=");   // 시험은 HTTP(COOKIE_SECURE=false)
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.body()).contains("\"code\":\"UNAUTHORIZED\"");           // 이제는 앱의 로그인 검사
        assertThat(config.statusCode()).isEqualTo(200);
    }

    private HttpRequest get(String path, String accept, String cookie) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).header("Accept", accept).GET();
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return b.build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
