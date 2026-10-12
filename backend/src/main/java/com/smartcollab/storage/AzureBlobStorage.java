package com.smartcollab.storage;

import com.azure.core.util.Context;
import com.azure.core.util.polling.LongRunningOperationStatus;
import com.azure.core.util.polling.PollResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobCopyInfo;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
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
    private static final long BLOCK_SIZE = 4L * 1024 * 1024;
    /** 4MB 블록, 한 번에 2블록 — 4MB 이하 파일은 한 번에 올림 */
    static final ParallelTransferOptions UPLOAD_TRANSFER = new ParallelTransferOptions()
            .setBlockSizeLong(BLOCK_SIZE).setMaxSingleUploadSizeLong(BLOCK_SIZE).setMaxConcurrency(2);

    private final BlobContainerClient container;

    public AzureBlobStorage(BlobContainerClient container) {
        this.container = container;
        container.createIfNotExists();
        log.info("Azure blob storage: {}", container.getBlobContainerUrl());
    }

    /**
     * 블록 단위로 올립니다 [H-1]. SDK 기본값(단일 업로드 상한 256MB)은 그 크기까지 내용을 모두 힙에 모은 뒤 한 번에 보내, 업로드 상한(200MB)
     * 파일 몇 개가 동시에 오면 운영 힙(1GB)을 넘을 수 있었습니다. 이제 업로드 한 건이 쓰는 메모리는 블록 크기 × 동시 블록 수 정도입니다.
     * 덮어쓰기 조건을 두지 않으므로 같은 키면 덮어씁니다(키는 늘 새로 만듦).
     */
    @Override
    public StoredBlob put(String key, InputStream content, long size) {
        HashingInputStream hashing = new HashingInputStream(content);
        container.getBlobClient(key).uploadWithResponse(
                new BlobParallelUploadOptions(hashing, size).setParallelTransferOptions(UPLOAD_TRANSFER), null, Context.NONE);
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
