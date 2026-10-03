package com.smartcollab.file;

import com.smartcollab.global.tx.TransactionRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장 사용량 집계(사용자·팀 행의 stored_bytes)를 실제 버전 합계와 맞춥니다 [IMP-01].
 * <p>집계는 버전을 넣고 지우는 트랜잭션에서 함께 고치므로 어긋나지 않아야 합니다. 그래도 직접 고친 DB·버그로 어긋나면 한도 판단이
 * 틀어지므로, 매일 어긋난 저장 공간만 찾아(전체를 한 번 합산) 하나씩 잠근 뒤 실제 합계로 바로잡고, 고친 수를 경고로 남깁니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageUsageReconciler {

    private final FileVersionRepository versions;
    private final StorageQuota quota;
    private final TransactionRunner tx;

    @Scheduled(cron = "${app.files.usage-reconcile-cron:0 30 4 * * *}", zone = "${app.files.trash-purge-zone:Asia/Seoul}")
    public void scheduled() {
        reconcile();
    }

    /** @return 바로잡은 저장 공간 수 */
    public int reconcile() {
        int fixed = 0;
        for (Long userId : versions.findUsersWithDriftedUsage()) {
            fixed += Boolean.TRUE.equals(tx.writeReadCommitted(() -> quota.recompute(StorageQuota.Scope.personal(userId)))) ? 1 : 0;
        }
        for (Long teamId : versions.findTeamsWithDriftedUsage()) {
            fixed += Boolean.TRUE.equals(tx.writeReadCommitted(() -> quota.recompute(StorageQuota.Scope.team(teamId)))) ? 1 : 0;
        }
        if (fixed > 0) {
            log.warn("Storage usage counters corrected: {} scope(s) differed from the actual version sum", fixed);
        }
        return fixed;
    }
}
