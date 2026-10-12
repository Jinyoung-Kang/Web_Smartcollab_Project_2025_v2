package com.smartcollab.storage;

import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
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

    /**
     * 서버 측 복사. 원본이 없으면 BlobNotFoundException, 복사가 성공으로 끝나지 않으면(실패·중단) 예외 [L-1] —
     * waitForCompletion 은 시간 초과 때만 예외를 던져, 실패한 복사본을 가리키는 버전이 커밋될 수 있었습니다.
     */
    @Override
    public void copy(String sourceKey, String targetKey) {
        BlobClient source = container.getBlobClient(sourceKey);
        String sourceUrl = source.getBlobUrl() + "?" + readSas(source, Duration.ofMinutes(10), null);
        PollResponse<BlobCopyInfo> done;
        try {
            done = container.getBlobClient(targetKey).beginCopy(sourceUrl, null).waitForCompletion(COPY_TIMEOUT);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                throw new BlobNotFoundException(sourceKey);
            }
            throw e;
        }
        if (done.getStatus() != LongRunningOperationStatus.SUCCESSFULLY_COMPLETED) {
            throw new IllegalStateException("Azure blob copy did not complete: " + done.getStatus());
        }
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
    public Optional<String> readOnlyUrl(String key, Duration ttl, String contentType) {
        BlobClient blob = container.getBlobClient(key);
        return Optional.of(blob.getBlobUrl() + "?" + readSas(blob, ttl, contentType));
    }

    @Override
    public String type() {
        return "azure";
    }

    private static String readSas(BlobClient blob, Duration ttl, String contentType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(now.plus(ttl),
                new BlobSasPermission().setReadPermission(true))
                .setStartTime(now.minusMinutes(1)); // 서버 간 시계 오차 허용
        // 응답 형식 덮어쓰기(rsct) — 저장된 Blob 은 확장자 없는 키·application/octet-stream 이라, 형식이 없으면
        // Office 웹 뷰어가 문서 종류를 몰라 "파일을 찾을 수 없음"으로 끝났습니다 [DEP-03]
        if (contentType != null) {
            values.setContentType(contentType);
        }
        return blob.generateSas(values);
    }
}
