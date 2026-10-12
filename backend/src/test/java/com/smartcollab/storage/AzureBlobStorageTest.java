package com.smartcollab.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Azure Blob 구현을 Azure Storage 에뮬레이터(Azurite)로 검증합니다. 운영과 같은 SDK 호출 경로를 탑니다.
 */
class AzureBlobStorageTest {

    // Azurite 공개 개발용 계정 키 (Microsoft 문서에 공개된 고정 값)
    static final String KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    // 서버 측 복사(Copy Blob)는 Azurite 가 원본 URL 을 스스로 조회하므로, 컨테이너 안팎에서 같은 주소가 되도록
    // 호스트와 컨테이너의 포트를 같은 번호로 맞춥니다.
    static final int PORT = freePort();
    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:latest")
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", String.valueOf(PORT), "--skipApiVersionCheck")
            .withExposedPorts(PORT)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(PORT), new ExposedPort(PORT))));

    static AzureBlobStorage storage;
    static String connectionString;

    @BeforeAll
    static void start() {
        AZURITE.start();
        connectionString = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + KEY
                + ";BlobEndpoint=http://localhost:" + PORT + "/devstoreaccount1;";
        storage = StorageConfig.azure(connectionString, "test");
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        AZURITE.stop();
    }

    @Test
    @DisplayName("업로드(SHA-256) → 읽기 → 서버 측 복사 → SAS URL 읽기 → 삭제")
    void roundTrip() throws Exception {
        byte[] bytes = "hello azure".getBytes(StandardCharsets.UTF_8);
        StoredBlob blob = storage.put("files/x", new ByteArrayInputStream(bytes), bytes.length);
        assertThat(blob.size()).isEqualTo(bytes.length);
        assertThat(blob.sha256()).hasSize(64);

        storage.copy("files/x", "files/y");
        try (InputStream in = storage.open("files/y")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello azure");
        }

        String url = storage.readOnlyUrl("files/y", Duration.ofMinutes(5), null).orElseThrow();
        HttpResponse<String> res = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("hello azure");

        storage.delete("files/x");
        storage.delete("files/x");
        assertThatThrownBy(() -> storage.open("files/x").read()).isInstanceOf(Exception.class);
    }

    /**
     * [DEP-03] Office 웹 뷰어는 주소 경로에 확장자가 없으면 응답 형식(Content-Type)으로 문서 종류를 정합니다. 저장 키(files/UUID)에는
     * 확장자가 없고 Blob 형식은 application/octet-stream 이라 Azure 에서 "파일을 찾을 수 없음"이 났습니다(qa/results/deploy-2026-10-12).
     * 미리보기 URL 이 응답 형식을 정해 주면 뷰어가 엽니다.
     */
    @Test
    @DisplayName("[DEP-03] 미리보기 URL 의 응답은 넘긴 형식(Content-Type)을 단다 — 저장 키에 확장자가 없어도 Office 뷰어가 문서 종류를 안다")
    void readOnlyUrlCarriesContentType() throws Exception {
        String xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        byte[] bytes = "PK-office".getBytes(StandardCharsets.UTF_8);
        String key = "files/office-" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length);

        String url = storage.readOnlyUrl(key, Duration.ofMinutes(5), xlsx).orElseThrow();
        HttpResponse<byte[]> res = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type")).contains(xlsx);
        assertThat(res.body()).isEqualTo(bytes);
    }

    /**
     * [H-1] 업로드는 파일 전체를 힙에 모으지 않아야 합니다. SDK 기본값(단일 업로드 상한 256MB)은 그 크기까지 내용을 모두 메모리에 모은 뒤
     * 한 번에 보내, 업로드 상한(200MB) 파일 몇 개가 동시에 오면 운영 힙(1GB)을 넘을 수 있었습니다. 128MB 힙의 자식 JVM 으로 200MB 를 올립니다.
     */
    @Test
    @DisplayName("[H-1] 128MB 힙에서도 200MB(업로드 상한) 파일을 올린다 — 파일 전체를 메모리에 모으지 않음")
    void uploadDoesNotBufferWholeFile() throws Exception {
        long size = 200L * 1024 * 1024;
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-Djdk.httpclient.allowRestrictedHeaders=content-length",
                "-cp", System.getProperty("java.class.path"), AzureUploadProbe.class.getName(),
                connectionString, "test", String.valueOf(size))
                .redirectErrorStream(true).start();
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = child.waitFor(3, TimeUnit.MINUTES);

        assertThat(finished).isTrue();
        assertThat(out).as(out.lines().filter(l -> l.contains("Error") || l.contains("OK")).findFirst().orElse(out))
                .contains("OK " + size).doesNotContain("OutOfMemoryError");
        assertThat(child.exitValue()).isZero();
    }

    /**
     * [M-3] 저장소가 연결은 받고 응답하지 않으면, SDK 기본값(시도당 상한 없음·4회)으로는 요청 하나가 284.3초 묶였습니다
     * (qa/results/deploy-2026-10-12/blob-unresponsive.txt). 시도 상한과 횟수를 두면 그 안에서 503 용 예외로 끝납니다.
     */
    @Test
    @DisplayName("[M-3] 응답 없는 저장소는 시도 상한 × 횟수 안에서 '잠시 쓸 수 없음'(503) 예외로 실패한다")
    void unresponsiveStorageFailsWithinBudget() throws Exception {
        try (ServerSocket blackHole = new ServerSocket(0)) {
            Thread.ofPlatform().daemon().start(() -> {
                while (!blackHole.isClosed()) {
                    try {
                        java.net.Socket s = blackHole.accept();
                        Thread.ofPlatform().daemon().start(() -> {
                            try (InputStream in = s.getInputStream()) {
                                in.transferTo(java.io.OutputStream.nullOutputStream());   // 읽기만 하고 답하지 않음
                            } catch (java.io.IOException ignored) {
                                // 닫힘
                            }
                        });
                    } catch (java.io.IOException e) {
                        return;
                    }
                }
            });
            String cs = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + KEY
                    + ";BlobEndpoint=http://127.0.0.1:" + blackHole.getLocalPort() + "/devstoreaccount1;";
            long started = System.nanoTime();

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    assertThatThrownBy(() -> StorageConfig.azure(cs, "black-hole", Duration.ofSeconds(2), 2))
                            .isInstanceOf(com.smartcollab.global.error.DependencyUnavailableException.class));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("[M-3] 기본 재시도 설정의 최악 시간이 App Service 앞단 제한(230초)보다 짧다")
    void defaultRetryBudgetFitsFrontEndTimeout() {
        Duration worst = StorageConfig.TRY_TIMEOUT.multipliedBy(StorageConfig.MAX_TRIES)
                .plus(StorageConfig.MAX_RETRY_DELAY.multipliedBy(StorageConfig.MAX_TRIES - 1L));

        assertThat(worst).isLessThan(Duration.ofSeconds(230));
    }

    @Test
    @DisplayName("[IMP-05] 접두어 아래의 키와 마지막 수정 시각을 나열한다 (고아 파일 정리용)")
    void listsKeysWithLastModified() {
        String prefix = "list-" + UUID.randomUUID() + "/";
        storage.put(prefix + "a", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), 1);
        storage.put(prefix + "b", new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)), 1);
        List<BlobStorage.Listed> listed = new ArrayList<>();

        storage.list(prefix, listed::add);

        assertThat(listed).extracting(BlobStorage.Listed::key).containsExactlyInAnyOrder(prefix + "a", prefix + "b");
        assertThat(listed).allSatisfy(b -> assertThat(b.lastModified()).isBefore(Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("[L-1] 없는 원본을 복사하면 BlobNotFoundException(404) — 예전에는 Azure 예외가 그대로 나가 500")
    void copyOfMissingSourceIsNotFound() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.copy("files/없는-원본", "files/대상"))
                .isInstanceOf(com.smartcollab.storage.BlobNotFoundException.class);
    }
}
