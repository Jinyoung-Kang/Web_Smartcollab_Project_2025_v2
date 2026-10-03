package com.smartcollab.global.error;

import com.smartcollab.global.web.RequestTraceFilter;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [QA-08] 경로에 {@code %00}·{@code %2F} 가 든 요청은 Tomcat 이 라우팅 전에 거절해 HTML 오류 페이지를, {@code //}·{@code ;} 가 든
 * 요청은 Spring Security 방화벽이 거절해 Boot 기본 JSON({@code timestamp·error·path})을 돌려주었습니다(출시 기준 QA 퍼징의 오류 형식
 * 불일치 87건). 다른 오류와 같은 problem+json 과 추적 ID 로 맞춥니다. 실제 Tomcat 으로 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreRoutingErrorFormatTest extends IntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(Api.Session s, String method, String rawPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Authorization", "Bearer " + s.cookie.getValue())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> res, String request) {
        assertThat(res.statusCode()).as(request).isEqualTo(400);
        assertThat(res.headers().firstValue("Content-Type")).as(request).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("status").asInt()).as(request).isEqualTo(400);
        assertThat(body.path("code").asString()).as(request).isEqualTo("INVALID_REQUEST");
        String requestId = res.headers().firstValue(RequestTraceFilter.HEADER).orElse(null);
        assertThat(requestId).as(request + " 추적 ID 헤더").isNotBlank();
        assertThat(body.path("requestId").asString()).as(request + " 본문의 추적 ID").isEqualTo(requestId);
        assertThat(res.body()).as(request).doesNotContain("<html", "timestamp", "Tomcat");
    }

    @Test
    @DisplayName("[QA-08] Tomcat 이 주소를 해석하지 못해 라우팅 전에 거절한 요청(%00·%2F)도 problem+json 400 과 추적 ID")
    void containerRejectionIsProblemJson() throws Exception {
        Api.Session s = api().signUp("qa08c");
        for (String request : List.of("GET /api/files/%00", "GET /api/files/..%2F..%2Fetc%2Fpasswd",
                "PATCH /api/folders/%00", "POST /api/teams/..%2F..%2Fetc%2Fpasswd/invitations")) {
            String[] parts = request.split(" ");
            assertProblem(send(s, parts[0], parts[1]), request);
        }
    }

    @Test
    @DisplayName("[QA-08] 오류 처리 경로(/error)를 직접 부르면 없는 주소처럼 problem+json 404")
    void directErrorPathIsNotFound() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = json.readTree(res.body());
        assertThat(body.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(body.path("requestId").asString()).isEqualTo(res.headers().firstValue(RequestTraceFilter.HEADER).orElseThrow());
    }

    @Test
    @DisplayName("[QA-08] 보안 방화벽이 거절한 요청(//·;)도 problem+json 400 과 추적 ID")
    void firewallRejectionIsProblemJson() throws Exception {
        Api.Session s = api().signUp("qa08f");
        for (String request : List.of("GET /api//files/1", "GET /api/files/1;x=1", "DELETE /api//folders/1")) {
            String[] parts = request.split(" ");
            assertProblem(send(s, parts[0], parts[1]), request);
        }
    }
}
