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
            return new AzureBlobStorage(new BlobContainerClientBuilder()
                    .connectionString(storage.azureConnectionString())
                    .containerName(storage.azureContainer())
                    .buildClient());
        }
        return new LocalBlobStorage(Path.of(storage.localRoot()));
    }
}
