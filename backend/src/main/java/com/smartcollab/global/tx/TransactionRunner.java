package com.smartcollab.global.tx;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 메서드 전체가 아니라 필요한 구간만 트랜잭션으로 감쌉니다.
 * 저장소(Azure 등 네트워크) 입출력처럼 오래 걸리는 작업을 트랜잭션 밖에 두어, 그동안 DB 커넥션을 붙잡지 않기 위함입니다 [PERF-01].
 */
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

    public void readOnly(Runnable work) {
        readOnly.executeWithoutResult(status -> work.run());
    }

    public <T> T readOnly(Supplier<T> work) {
        return readOnly.execute(status -> work.get());
    }
}
