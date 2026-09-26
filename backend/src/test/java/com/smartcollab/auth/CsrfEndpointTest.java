package com.smartcollab.auth;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSRF 토큰 발급 API 의 계약을 실제 서버(랜덤 포트)로 확인합니다.
 * (MockMvc 의 csrf() 테스트 도우미가 공유 CSRF 필터의 저장소를 바꾸므로, 이 계약은 실제 HTTP 로 검증합니다)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CsrfEndpointTest extends IntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("CSRF 발급 API 가 돌려주는 토큰은 쿠키와 같은 값이고, 그대로 X-XSRF-TOKEN 헤더에 쓸 수 있다")
    void issuedTokenIsUsableAsHeader() throws Exception {
        HttpResponse<String> issued = http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String cookie = issued.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
                .findFirst().orElseThrow();
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(issued.body());
        assertThat(m.find()).isTrue();
        String bodyToken = m.group(1);

        assertThat(bodyToken).isEqualTo(cookie);
        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                        .header("Cookie", "XSRF-TOKEN=" + cookie)
                        .header("X-XSRF-TOKEN", bodyToken)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("[BUG-08] 로그인한 요청마다 CSRF 쿠키를 바꾸지 않는다 (요청이 겹치면 헤더와 쿠키가 어긋나 403 이 났음)")
    void authenticatedRequestsKeepCsrfCookie() throws Exception {
        String[] session = signUp();
        HttpResponse<String> teams = http.send(HttpRequest.newBuilder(uri("/api/teams"))
                        .header("Cookie", "SC_AUTH=" + session[0] + "; XSRF-TOKEN=" + session[1]).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(teams.statusCode()).isEqualTo(200);
        assertThat(teams.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("XSRF-TOKEN="));
    }

    @Test
    @DisplayName("[BUG-08] 로그인·가입 응답은 CSRF 쿠키를 지워, 로그인 뒤에는 새 토큰을 받게 한다")
    void loginRotatesCsrfToken() throws Exception {
        String[] session = signUp();
        assertThat(session[2]).as("가입 응답의 XSRF-TOKEN Set-Cookie").contains("Max-Age=0");
    }

    /** 실제 HTTP 로 가입해 [인증 쿠키, 가입 전 CSRF 토큰, 가입 응답의 XSRF-TOKEN Set-Cookie] 를 돌려줍니다. */
    private String[] signUp() throws Exception {
        String csrf = cookie(http.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()), "XSRF-TOKEN");
        String username = "csrf" + System.nanoTime() % 1_000_000_000;
        HttpResponse<String> signup = http.send(HttpRequest.newBuilder(uri("/api/auth/signup"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + csrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username
                                + "\",\"password\":\"passw0rd!\",\"passwordConfirm\":\"passw0rd!\",\"name\":\"csrf\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(signup.statusCode()).isEqualTo(201);
        String xsrfSetCookie = signup.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElse("");
        return new String[]{cookie(signup, "SC_AUTH"), csrf, xsrfSetCookie};
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .findFirst().orElseThrow();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
