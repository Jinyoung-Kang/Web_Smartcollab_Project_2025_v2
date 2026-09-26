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

    @Test
    @DisplayName("CSRF 발급 API 가 돌려주는 토큰은 쿠키와 같은 값이고, 그대로 X-XSRF-TOKEN 헤더에 쓸 수 있다")
    void issuedTokenIsUsableAsHeader() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
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

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
