package com.smartcollab.storage;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C-1] 운영(App Service B1 = 1 vCPU)처럼 CPU 가 1개로 보이는 JVM 에서 큰 파일을 내려받아도 서버가 멈추지 않아야 합니다.
 * <p>Java 21 가상 스레드는 {@code synchronized} 안에서 기다리면 캐리어 스레드에 묶이는데, Azure SDK 의 {@code BlobInputStream.read} 가
 * 그 안에서 동기 다운로드를 기다리고 SDK 의 응답 처리도 가상 스레드에서 돕니다. 캐리어가 1개면 30MB 한 건으로 서버 전체가 영구히
 * 멈췄습니다(헬스 체크 무응답, qa/results/review-2026-10-12b). 이 시험은 {@code singleCarrierTest} 작업(-XX:ActiveProcessorCount=1)에서만 돕니다.</p>
 */
@Tag("single-carrier")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SingleCarrierDownloadTest extends IntegrationTest {

    /** Azure SDK 가 한 번에 읽는 덩어리(4MB)보다 여러 배 큰 파일 */
    private static final int SIZE = 32 * 1024 * 1024;
    static final int AZURITE_PORT = AzureBlobStorageTest.freePort();
    /** 시험 중에만 쓰는 자체 서명 인증서의 저장소 비밀번호(비밀값 아님) */
    private static final String STORE_PASSWORD = "single-carrier";
    static final GenericContainer<?> AZURITE;

    /*
     * 운영처럼 HTTPS 로 띄웁니다. JDK HttpClient 는 TLS 복호화를 실행기(Azure SDK 기본값이 가상 스레드)에 넘기는데, 평문 HTTP 의
     * Azurite 에서는 이 단계가 없어 같은 조건에서도 멈추지 않았습니다. 인증서는 keytool 로 만들어 이 JVM 의 신뢰 저장소로 씁니다.
     */
    static {
        try {
            Path dir = Files.createTempDirectory("azurite-tls");
            Path server = dir.resolve("azurite.pfx");
            Path cert = dir.resolve("azurite.pem");
            Path trust = dir.resolve("trust.p12");
            keytool("-genkeypair", "-alias", "azurite", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", server.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
            keytool("-exportcert", "-rfc", "-alias", "azurite", "-keystore", server.toString(), "-storepass", STORE_PASSWORD,
                    "-file", cert.toString());
            keytool("-importcert", "-noprompt", "-alias", "azurite", "-file", cert.toString(), "-storetype", "PKCS12",
                    "-keystore", trust.toString(), "-storepass", STORE_PASSWORD);
            System.setProperty("javax.net.ssl.trustStore", trust.toString());
            System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD);
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:latest")
                    .withCopyFileToContainer(MountableFile.forHostPath(server), "/certs/azurite.pfx")
                    .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", String.valueOf(AZURITE_PORT),
                            "--skipApiVersionCheck", "--cert", "/certs/azurite.pfx", "--pwd", STORE_PASSWORD)
                    .withExposedPorts(AZURITE_PORT)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(AZURITE_PORT), new ExposedPort(AZURITE_PORT))))
                    .waitingFor(Wait.forListeningPort());
            AZURITE.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void keytool(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool 실패: " + out);
        }
    }

    @DynamicPropertySource
    static void azureStorage(DynamicPropertyRegistry r) {
        r.add("app.storage.type", () -> "azure");
        r.add("app.storage.azure-container", () -> "single-carrier");
        r.add("app.storage.azure-connection-string", () -> "DefaultEndpointsProtocol=https;AccountName=devstoreaccount1;AccountKey="
                + AzureBlobStorageTest.KEY + ";BlobEndpoint=https://localhost:" + AZURITE_PORT + "/devstoreaccount1;");
    }

    @LocalServerPort
    int port;

    @Autowired
    BlobStorage storage;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("[C-1] CPU 1개에서도 큰 파일 내려받기가 끝나고, 그동안 다른 요청(헬스 체크)이 응답한다")
    void largeDownloadDoesNotFreezeServer() throws Exception {
        assertThat(Runtime.getRuntime().availableProcessors()).as("singleCarrierTest 작업의 JVM").isEqualTo(1);
        byte[] content = new byte[SIZE];
        new Random(42).nextBytes(content);
        long fileId = signUpAndUpload(content);

        CompletableFuture<HttpResponse<byte[]>> download = http.sendAsync(
                get("/api/files/" + fileId + "/download").timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        Thread.sleep(500);   // 내려받기가 저장소를 읽기 시작하도록
        HttpResponse<String> health = http.send(get("/actuator/health/liveness").timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<byte[]> res = download.get(60, TimeUnit.SECONDS);

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(content);
    }

    @Test
    @DisplayName("[C-1] CPU 1개에서도 영구 삭제 뒤 저장소 파일이 지워진다 (커밋 뒤 삭제는 별도 가상 스레드 [P-02])")
    void deleteAfterCommitCompletes() throws Exception {
        byte[] content = new byte[5 * 1024 * 1024];
        new Random(7).nextBytes(content);
        long fileId = signUpAndUpload(content);
        int before = countBlobs();

        assertThat(http.send(delete("/api/files/" + fileId), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        assertThat(http.send(delete("/api/trash/" + fileId), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (countBlobs() >= before && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(countBlobs()).isEqualTo(before - 1);
    }

    private int countBlobs() {
        int[] n = {0};
        storage.list("files/", b -> n[0]++);
        return n[0];
    }

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-XSRF-TOKEN", xsrf()).DELETE().build();
    }

    private long signUpAndUpload(byte[] content) throws Exception {
        http.send(get("/api/auth/csrf").build(), HttpResponse.BodyHandlers.discarding());
        String user = "sc" + UUID.randomUUID().toString().substring(0, 8);
        String signUp = "{\"username\":\"" + user + "\",\"password\":\"Passw0rd1234\",\"passwordConfirm\":\"Passw0rd1234\","
                + "\"name\":\"한 코어\",\"email\":\"" + user + "@example.com\"}";
        assertThat(http.send(post("/api/auth/signup", "application/json", signUp.getBytes(StandardCharsets.UTF_8)),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
        String me = http.send(get("/api/auth/me").build(), HttpResponse.BodyHandlers.ofString()).body();
        long root = number(me, "rootFolderId");

        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> uploaded = http.send(post("/api/files/upload?folderId=" + root,
                "multipart/form-data; boundary=" + boundary, body.toByteArray()), HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        return number(uploaded.body(), "id");
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Accept", "*/*");
    }

    private HttpRequest post(String path, String contentType, byte[] body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType).header("X-XSRF-TOKEN", xsrf())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private String xsrf() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElseThrow();
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        assertThat(m.find()).as(field + " in " + json).isTrue();
        return Long.parseLong(m.group(1));
    }
}
