package com.smartcollab.global.web;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [QA-03] 업로드 요청의 multipart 본문을 해석하지 못하면(끝 경계 없음, 파일 이름에 NUL, 업로드 도중 연결 끊김) 처리하지 않은
 * 예외로 500 과 스택이 담긴 ERROR 로그가 남았습니다. 연결 끊김은 화면의 "업로드 취소"와 같아 평소에도 생기는 일이라,
 * 취소할 때마다 오류 경보가 울리게 됩니다(QA 스택에서 재현, qa/scripts/multipart-edge.mjs). 실제 Tomcat 으로 확인합니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class MultipartErrorTest extends IntegrationTest {

    private static final String BOUNDARY = "qa-boundary";

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> upload(Api.Session s, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/upload?folderId=" + s.rootFolderId))
                .header("Authorization", "Bearer " + s.cookie.getValue())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String part(String filename, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n" + content;
    }

    /** marker 이후에 처리하지 않은 예외 로그가 없어야 합니다 */
    private static void assertNoUnhandledErrorAfter(CapturedOutput output, String marker) {
        String after = output.getAll().substring(output.getAll().indexOf(marker));
        assertThat(after).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("[QA-03] 끝 경계가 없는 multipart 본문은 400 이고 ERROR 로그를 남기지 않는다")
    void truncatedMultipartIsBadRequest(CapturedOutput output) throws Exception {
        Api.Session s = api().signUp("qa03t");
        String marker = "QA-03-" + UUID.randomUUID();
        System.out.println(marker);

        HttpResponse<String> res = upload(s, part("a.txt", "hello"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertNoUnhandledErrorAfter(output, marker);
    }

    @Test
    @DisplayName("[QA-03] 파일 이름에 NUL 문자가 있으면 400 이고 ERROR 로그를 남기지 않는다")
    void nulInFilenameIsBadRequest(CapturedOutput output) throws Exception {
        Api.Session s = api().signUp("qa03n");
        String marker = "QA-03-" + UUID.randomUUID();
        System.out.println(marker);

        HttpResponse<String> res = upload(s, part("name\u0000.txt", "hello") + "\r\n--" + BOUNDARY + "--\r\n");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertNoUnhandledErrorAfter(output, marker);
    }

    @Test
    @DisplayName("[QA-03] 업로드 도중 연결이 끊겨도(업로드 취소) 처리하지 않은 예외로 ERROR 로그를 남기지 않고, 파일도 남지 않는다")
    void abortedUploadIsNotAnError(CapturedOutput output) throws Exception {
        Api.Session s = api().signUp("qa03a");
        String marker = "QA-03-" + UUID.randomUUID();
        System.out.println(marker);

        String head = part("big.bin", "");
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/files/upload?folderId=" + s.rootFolderId + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Authorization: Bearer " + s.cookie.getValue() + "\r\n"
                    + "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n"
                    + "Content-Length: 50000000\r\n\r\n" + head).getBytes(StandardCharsets.UTF_8));
            out.write(new byte[3 * 1024 * 1024]);
            out.flush();
        }   // 나머지를 보내지 않고 연결을 끊음

        // 서버가 끊긴 연결을 처리할 시간을 줍니다
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !output.getAll().substring(output.getAll().indexOf(marker)).contains("EOFException")) {
            Thread.sleep(100);
        }
        Thread.sleep(300);
        assertNoUnhandledErrorAfter(output, marker);
        assertThat(jdbc.queryForObject("select count(*) from files where folder_id = ?", Integer.class, s.rootFolderId)).isZero();
    }
}
