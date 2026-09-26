package com.smartcollab.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * DB 트랜잭션과 저장소(외부 시스템) 사이의 일관성을 맞춥니다.
 * <ul>
 *   <li>업로드: 저장소에 먼저 쓰고, DB 트랜잭션이 롤백되면 방금 쓴 파일을 지웁니다 (고아 파일 방지).</li>
 *   <li>삭제: DB 커밋이 끝난 뒤에만 저장소에서 지웁니다. 커밋 전에 지웠다가 롤백되면 DB 는 남고 내용은 사라지기 때문입니다.</li>
 * </ul>
 * v1 은 트랜잭션 도중 Blob 을 먼저 삭제해, 이후 DB 오류가 나면 복구할 수 없는 상태가 될 수 있었습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlobLifecycle {

    private final BlobStorage storage;

    public static String newFileKey() {
        return "files/" + UUID.randomUUID();
    }

    public StoredBlob putWithRollbackCleanup(String key, java.io.InputStream content, long size) {
        StoredBlob blob = storage.put(key, content, size);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteQuietly(List.of(key));
                    }
                }
            });
        }
        return blob;
    }

    public void copyWithRollbackCleanup(String sourceKey, String targetKey) {
        storage.copy(sourceKey, targetKey);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteQuietly(List.of(targetKey));
                    }
                }
            });
        }
    }

    public void deleteAfterCommit(Collection<String> keys) {
        if (keys.isEmpty()) return;
        List<String> snapshot = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(snapshot);
                }
            });
        } else {
            deleteQuietly(snapshot);
        }
    }

    private void deleteQuietly(Collection<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                // 저장소 삭제 실패는 고아 파일만 남길 뿐 사용자 데이터 정합성은 깨지지 않습니다.
                log.warn("Blob delete failed (orphan left): {}", key, e);
            }
        }
    }
}
