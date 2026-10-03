package com.smartcollab.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Azure Blob Storage 저장소 (운영).
 */
@Slf4j
public class AzureBlobStorage implements BlobStorage {

    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(5);

    private final BlobContainerClient container;

    public AzureBlobStorage(BlobContainerClient container) {
        this.container = container;
        container.createIfNotExists();
        log.info("Azure blob storage: {}", container.getBlobContainerUrl());
    }

    @Override
    public StoredBlob put(String key, InputStream content, long size) {
        HashingInputStream hashing = new HashingInputStream(content);
        container.getBlobClient(key).upload(hashing, size, true);
        return new StoredBlob(key, hashing.count(), hashing.sha256Hex());
    }

    @Override
    public InputStream open(String key) {
        try {
            return container.getBlobClient(key).openInputStream();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                throw new BlobNotFoundException(key);
            }
            throw e;
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        BlobClient source = container.getBlobClient(sourceKey);
        String sourceUrl = source.getBlobUrl() + "?" + readSas(source, Duration.ofMinutes(10));
        container.getBlobClient(targetKey).beginCopy(sourceUrl, null).waitForCompletion(COPY_TIMEOUT);
    }

    @Override
    public void delete(String key) {
        container.getBlobClient(key).deleteIfExists();
    }

    @Override
    public void list(String prefix, Consumer<Listed> sink) {
        container.listBlobs(new ListBlobsOptions().setPrefix(prefix), null).forEach(item ->
                sink.accept(new Listed(item.getName(), item.getProperties().getLastModified().toInstant())));
    }

    @Override
    public Optional<String> readOnlyUrl(String key, Duration ttl) {
        BlobClient blob = container.getBlobClient(key);
        return Optional.of(blob.getBlobUrl() + "?" + readSas(blob, ttl));
    }

    @Override
    public String type() {
        return "azure";
    }

    private static String readSas(BlobClient blob, Duration ttl) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(now.plus(ttl),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(now.minusMinutes(1)); // 서버 간 시계 오차 허용
        return blob.generateSas(values);
    }
}
