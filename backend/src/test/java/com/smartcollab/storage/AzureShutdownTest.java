package com.smartcollab.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [QA6-01] 앱이 정상 종료(SIGTERM — 배포·설정 변경·재시작마다)하는 동안 진행 중인 업로드가 500 으로 실패했습니다.
 * Azure SDK 의 HTTP 클라이언트가 실행기를 지정하지 않아 SDK 공용 실행기(SharedExecutorService)를 쓰는데, 이 실행기는 자기 JVM 종료 훅으로
 * 먼저 닫히고, 그 뒤의 호출은 실행기를 다시 만들며 종료 훅을 등록하다 IllegalStateException("Shutdown in progress")으로 실패합니다.
 * 6차 QA 블랙박스: QA 스택(Azure 저장소)에서 업로드 중 SIGTERM → 500, 로컬 저장소였던 10-03 QA 에서는 201.
 */
class AzureShutdownTest {

    static final GenericContainer<?> AZURITE = new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:latest")
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
            .withExposedPorts(10000);

    @BeforeAll
    static void start() {
        AZURITE.start();
    }

    @AfterAll
    static void stop() {
        AZURITE.stop();
    }

    @Test
    @DisplayName("[QA6-01] JVM 이 종료되는 중(정상 종료)에도 Azure 저장소에 쓸 수 있다 — SDK 공용 실행기가 먼저 닫혀 진행 중인 업로드가 500 이던 문제")
    void storageStillWorksDuringJvmShutdown() throws Exception {
        String connectionString = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + AzureBlobStorageTest.KEY
                + ";BlobEndpoint=http://localhost:" + AZURITE.getMappedPort(10000) + "/devstoreaccount1;";
        Process probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), AzureShutdownProbe.class.getName(), connectionString)
                .redirectErrorStream(true)
                .start();
        String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(probe.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(output).as(output).contains("PROBE OK").doesNotContain("PROBE FAIL");
    }
}
