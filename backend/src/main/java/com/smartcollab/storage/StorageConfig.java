package com.smartcollab.storage;

import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.util.SharedExecutorService;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.smartcollab.global.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class StorageConfig {

    @Bean
    BlobStorage blobStorage(AppProperties props) {
        AppProperties.Storage storage = props.storage();
        if (storage.isAzure()) {
            if (!StringUtils.hasText(storage.azureConnectionString())) {
                throw new IllegalStateException("STORAGE_TYPE=azure 에는 AZURE_STORAGE_CONNECTION_STRING 이 필요합니다.");
            }
            return azure(storage.azureConnectionString(), storage.azureContainer());
        }
        return new LocalBlobStorage(Path.of(storage.localRoot()));
    }

    /*
     * 재시도 [M-3]. SDK 기본값(시도당 상한 없음·4회·대기 최대 120초)이면 응답 없는 저장소에 요청 하나가 284.3초 묶였습니다(실측) —
     * App Service 앞단 제한(230초)보다 길어, 사용자는 오류를 받는데 서버는 이어서 커밋할 수 있었습니다.
     * 업로드는 4MB 블록, 내려받기도 4MB 덩어리라 시도 하나에 30초면 충분합니다. 최악 30초 × 3 + 대기(1·2초) ≈ 93초.
     */
    static final Duration TRY_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_TRIES = 3;
    static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(4);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /*
     * Azure SDK 공용 실행기를 이 앱이 관리하는 실행기로 바꿉니다 [QA6-01]. SDK 가 만든 공용 실행기(SharedExecutorService)는 자기 JVM 종료 훅으로
     * 먼저 닫히는데, HTTP 클라이언트의 비동기 작업과 응답 시간 상한(읽기·응답 시간 초과 예약)이 모두 이 실행기를 씁니다. 그래서 정상 종료
     * (배포·설정 변경마다)가 진행 중인 요청을 기다리는 동안 그 요청의 저장소 호출이 "Shutdown in progress"(500)로 실패하거나 응답을 받지 못했습니다.
     * SDK 가 정해 둔 교체 방법(setExecutorService)으로 바꾸면 종료 훅이 걸리지 않습니다. 나머지는 SDK 기본값과 같게 둡니다 — 크기 CPU 수 × 10
     * (내려받기의 읽기 하나가 스레드 하나를 씀), 유지 60초, 가상 스레드(Java 21 의 SDK 기본값. 플랫폼 스레드로 바꾸면 스레드마다 4MB 임시 직접
     * 버퍼를 붙들어 128MB 힙 시험[H-1]에서 직접 메모리가 바닥남). 가상 스레드는 데몬이라 JVM 종료를 막지 않습니다.
     */
    static final ScheduledExecutorService AZURE_SDK_EXECUTOR = azureSdkExecutor();

    private static ScheduledExecutorService azureSdkExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(10 * Runtime.getRuntime().availableProcessors(),
                Thread.ofVirtual().name("azure-sdk-", 0).factory());
        executor.setKeepAliveTime(60, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        SharedExecutorService.getInstance().setExecutorService(executor);
        return executor;
    }

    /** Azure 저장소 — 시험도 이 메서드로 만들어 운영과 같은 클라이언트 설정을 씁니다. */
    static AzureBlobStorage azure(String connectionString, String containerName) {
        return azure(connectionString, containerName, TRY_TIMEOUT, MAX_TRIES);
    }

    static AzureBlobStorage azure(String connectionString, String containerName, Duration tryTimeout, int maxTries) {
        return new AzureBlobStorage(new BlobContainerClientBuilder()
                .connectionString(connectionString)
                .containerName(containerName)
                // 동기 호출에서는 재시도 정책의 시도당 상한이 듣지 않아(응답 없는 저장소 시험으로 확인), HTTP 클라이언트의
                // 응답·읽기·쓰기 상한(기본 각 60초)에도 같은 값을 줍니다.
                .httpClient(new JdkHttpClientBuilder().connectionTimeout(CONNECT_TIMEOUT)
                        .writeTimeout(tryTimeout).responseTimeout(tryTimeout).readTimeout(tryTimeout).build())
                .retryOptions(new RequestRetryOptions(RetryPolicyType.EXPONENTIAL, maxTries, tryTimeout, RETRY_DELAY,
                        MAX_RETRY_DELAY, null))
                .buildClient());
    }
}
