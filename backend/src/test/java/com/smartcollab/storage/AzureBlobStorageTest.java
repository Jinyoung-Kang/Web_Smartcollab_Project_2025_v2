package com.smartcollab.storage;

import com.azure.storage.blob.BlobContainerClientBuilder;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @BeforeAll
    static void start() {
        AZURITE.start();
        String cs = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + KEY
                + ";BlobEndpoint=http://localhost:" + PORT + "/devstoreaccount1;";
        storage = new AzureBlobStorage(new BlobContainerClientBuilder().connectionString(cs).containerName("test").buildClient());
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

        String url = storage.readOnlyUrl("files/y", Duration.ofMinutes(5)).orElseThrow();
        HttpResponse<String> res = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("hello azure");

        storage.delete("files/x");
        storage.delete("files/x");
        assertThatThrownBy(() -> storage.open("files/x").read()).isInstanceOf(Exception.class);
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
}
