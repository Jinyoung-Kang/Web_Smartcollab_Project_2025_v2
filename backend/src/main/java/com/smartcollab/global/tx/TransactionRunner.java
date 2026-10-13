package com.smartcollab.global.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 메서드 전체가 아니라 필요한 구간만 트랜잭션으로 감쌉니다.
 * 저장소(Azure 등 네트워크) 입출력처럼 오래 걸리는 작업을 트랜잭션 밖에 두어, 그동안 DB 커넥션을 붙잡지 않기 위함입니다 [PERF-01].
 */
@Slf4j
@Component
public class TransactionRunner {

    private final TransactionTemplate writeReadCommitted;
    private final TransactionTemplate readOnly;

    public TransactionRunner(PlatformTransactionManager transactionManager) {
        this.writeReadCommitted = new TransactionTemplate(transactionManager);
        this.writeReadCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * 저장 공간(사용자·팀 행)을 잠근 뒤 최신 커밋 데이터를 읽어야 하는 쓰기 — 폴더 구조 변경 [S-06], 저장 한도 확인이 있는
     * 업로드·텍스트 저장·복사 [QA-06]. READ COMMITTED 는 검색 범위의 간격을 잠그지 않아 다른 사용자의 쓰기와 교착되지 않습니다.
     */
    public <T> T writeReadCommitted(Supplier<T> work) {
        return writeReadCommitted.execute(status -> work.get());
    }

    /**
     * {@link #writeReadCommitted(Supplier)} 와 같고, 확실히 롤백됐을 때만 {@code onRolledBack} 을 부릅니다 [H-4].
     * 작업 안에서 난 실패는 롤백이 확실하지만, 작업을 마친 뒤 커밋 단계에서 난 실패는 DB 에 커밋됐는지 알 수 없습니다
     * (커밋은 됐는데 확인 응답을 못 받는 경우). 그때 미리 써 둔 저장소 파일을 지우면 커밋된 버전이 내용을 잃으므로 아무것도 하지 않습니다 —
     * 정말 롤백됐다면 그 파일은 고아 파일 정리(24시간 유예)가 지웁니다.
     */
    public <T> T writeReadCommitted(Supplier<T> work, Runnable onRolledBack) {
        boolean[] workDone = {false};
        try {
            return writeReadCommitted.execute(status -> {
                T result = work.get();
                workDone[0] = true;
                return result;
            });
        } catch (RuntimeException | Error e) {
            if (!workDone[0]) {
                onRolledBack.run();
            } else {
                log.warn("Write transaction failed while committing; outcome unknown, leaving files to the orphan cleaner: {}",
                        e.getClass().getSimpleName());
            }
            throw e;
        }
    }

    static final int MAX_LOCK_CONFLICT_ATTEMPTS = 3;

    /**
     * {@link #writeReadCommitted(Supplier)} 와 같고, 교착(MySQL 1213)·잠금 대기 초과로 롤백되면 처음부터 다시 실행합니다(최대 3번) [QA6-06].
     * 교착은 DB 가 한쪽 트랜잭션을 통째로 롤백하므로 다시 해도 안전합니다. 계정 삭제처럼 여러 표를 지우며 다른 요청(그 사람의 팀 업로드 —
     * 팀 행 → 사용자 행 외래 키 순서)과 잠금 순서가 엇갈릴 수 있는 쓰기에만 씁니다. 이미 바깥 트랜잭션 안이면 다시 할 수 없어 한 번만 실행합니다.
     */
    public <T> T writeReadCommittedRetryingLockConflicts(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return writeReadCommitted.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_LOCK_CONFLICT_ATTEMPTS) {
                    throw e;
                }
                log.warn("Write transaction rolled back by a lock conflict ({}); retrying ({}/{})",
                        e.getClass().getSimpleName(), attempt + 1, MAX_LOCK_CONFLICT_ATTEMPTS);
            }
        }
    }

    public void readOnly(Runnable work) {
        readOnly.executeWithoutResult(status -> work.run());
    }

    public <T> T readOnly(Supplier<T> work) {
        return readOnly.execute(status -> work.get());
    }
}
