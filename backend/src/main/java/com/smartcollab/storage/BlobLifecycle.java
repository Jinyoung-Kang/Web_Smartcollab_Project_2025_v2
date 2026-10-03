package com.smartcollab.storage;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * DB 트랜잭션과 저장소(외부 시스템) 사이의 일관성을 맞춥니다.
 * <ul>
 *   <li>업로드·복사·텍스트 저장: 저장소 작업은 DB 트랜잭션 밖에서 먼저 하고, 이어지는 DB 저장이 실패하면 방금 쓴 파일을
 *       지웁니다({@link #discard}).</li>
 *   <li>삭제: DB 커밋이 끝난 뒤에만 저장소에서 지웁니다. 커밋 전에 지웠다가 롤백되면 DB 는 남고 내용은 사라지기 때문입니다.
 *       지우는 일은 별도 가상 스레드에서 합니다 [P-02] — 커밋 직후 콜백은 아직 DB 커넥션을 쥐고 있어, 그 자리에서 파일마다
 *       저장소를 부르면 휴지통 비우기·팀 삭제·탈퇴 동안 커넥션을 붙잡았습니다. 서버 종료 때는 진행 중인 삭제를 기다립니다.</li>
 * </ul>
 * v1 은 트랜잭션 도중 Blob 을 먼저 삭제해, 이후 DB 오류가 나면 복구할 수 없는 상태가 될 수 있었습니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlobLifecycle {

    private final BlobStorage storage;
    private final ExecutorService deleter = Executors.newVirtualThreadPerTaskExecutor();

    public static String newFileKey() {
        return "files/" + UUID.randomUUID();
    }

    /** 트랜잭션 밖에서 미리 써 둔 파일을, 이어지는 DB 저장이 실패했을 때 지웁니다 (실패해도 고아 파일만 남음). */
    public void discard(Collection<String> keys) {
        deleteQuietly(List.copyOf(keys));
    }

    public void deleteAfterCommit(Collection<String> keys) {
        if (keys.isEmpty()) return;
        List<String> snapshot = List.copyOf(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteInBackground(snapshot);
                }
            });
        } else {
            deleteQuietly(snapshot);
        }
    }

    private void deleteInBackground(List<String> keys) {
        try {
            deleter.execute(() -> deleteQuietly(keys));
        } catch (RejectedExecutionException shuttingDown) {
            deleteQuietly(keys);
        }
    }

    @PreDestroy
    void awaitPendingDeletes() throws InterruptedException {
        deleter.shutdown();
        if (!deleter.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("Blob deletes still running at shutdown (orphans may be left)");
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
