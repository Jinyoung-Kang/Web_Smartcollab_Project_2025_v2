package com.smartcollab.storage;

import com.azure.storage.blob.BlobContainerClientBuilder;
import com.smartcollab.global.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;

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

    /** 시도 하나의 시간 상한 — 아직 SDK 기본값(사실상 없음) [M-3] */
    static final Duration TRY_TIMEOUT = Duration.ofSeconds(Integer.MAX_VALUE);
    static final int MAX_TRIES = 4;
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(120);

    /** Azure 저장소 — 시험도 이 메서드로 만들어 운영과 같은 클라이언트 설정을 씁니다. */
    static AzureBlobStorage azure(String connectionString, String containerName) {
        return azure(connectionString, containerName, TRY_TIMEOUT, MAX_TRIES);
    }

    static AzureBlobStorage azure(String connectionString, String containerName, Duration tryTimeout, int maxTries) {
        return new AzureBlobStorage(new BlobContainerClientBuilder()
                .connectionString(connectionString)
                .containerName(containerName)
                .buildClient());
    }
}
