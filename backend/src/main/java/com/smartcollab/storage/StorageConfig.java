package com.smartcollab.storage;

import com.azure.storage.blob.BlobContainerClientBuilder;
import com.smartcollab.global.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

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

    /** Azure 저장소 — 시험도 이 메서드로 만들어 운영과 같은 클라이언트 설정을 씁니다. */
    static AzureBlobStorage azure(String connectionString, String containerName) {
        return new AzureBlobStorage(new BlobContainerClientBuilder()
                .connectionString(connectionString)
                .containerName(containerName)
                .buildClient());
    }
}
