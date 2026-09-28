package com.smartcollab.global.web;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [SEC-10] 요청 본문 크기 제한. 제한이 없으면 로그인하지 않은 사용자도 공개 API 에 수십 MB 의 JSON 을 보내
 * 서버가 끝까지 읽어 메모리에 올리게 할 수 있었습니다(60MB 로 재현). 실제 서버(Tomcat)로 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestBodyLimitTest extends IntegrationTest {

    /** 기본 한도(6MB)를 넘는 크기 */
    private static final int OVER_LIMIT = 7 * 1024 * 1024;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("한도를 넘는 JSON 본문은 읽기 전에 413 으로 거절한다 (Content-Length)")
    void rejectsOversizedBodyByContentLength() throws Exception {
        HttpResponse<String> res = http.send(unlock(HttpRequest.BodyPublishers.ofString(json(OVER_LIMIT))),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
    }

    @Test
    @DisplayName("길이를 알리지 않는 전송(chunked)도 한도를 넘는 순간 413 으로 거절한다")
    void rejectsOversizedChunkedBody() throws Exception {
        byte[] body = json(OVER_LIMIT).getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> res = http.send(unlock(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
    }

    @Test
    @DisplayName("한도 안의 본문은 평소대로 처리한다")
    void acceptsBodyWithinLimit() throws Exception {
        HttpResponse<String> res = http.send(unlock(HttpRequest.BodyPublishers.ofString(json(1024 * 1024))),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(404);   // 없는 링크
    }

    @Test
    @DisplayName("파일 업로드(multipart)는 업로드 한도를 따르므로 이 제한을 받지 않는다")
    void multipartUploadIsNotLimitedByBodyLimit() throws Exception {
        Api.Session s = api().signUp("bodylimit");

        s.upload(s.rootFolderId, "big.bin", new byte[OVER_LIMIT]).andExpect(status().isCreated());
    }

    private HttpRequest unlock(HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/shares/no-such-token/unlock"))
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
    }

    private static String json(int size) {
        return "{\"password\":\"" + "a".repeat(size) + "\"}";
    }
}
