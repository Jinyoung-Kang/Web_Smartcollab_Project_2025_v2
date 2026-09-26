package com.smartcollab.global.tx;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 메서드 전체가 아니라 필요한 구간만 트랜잭션으로 감쌉니다.
 * 저장소(Azure 등 네트워크) 입출력처럼 오래 걸리는 작업을 트랜잭션 밖에 두어, 그동안 DB 커넥션을 붙잡지 않기 위함입니다 [PERF-01].
 */
@Component
public class TransactionRunner {

    private final TransactionTemplate write;
    private final TransactionTemplate readOnly;

    public TransactionRunner(PlatformTransactionManager transactionManager) {
        this.write = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public <T> T write(Supplier<T> work) {
        return write.execute(status -> work.get());
    }

    public void readOnly(Runnable work) {
        readOnly.executeWithoutResult(status -> work.run());
    }

    public <T> T readOnly(Supplier<T> work) {
        return readOnly.execute(status -> work.get());
    }
}
