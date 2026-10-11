package com.smartcollab.global.tx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [H-4] 확실히 롤백됐을 때만 정리하고, 커밋 단계 실패(결과 모름)에서는 정리하지 않는다 */
class TransactionRunnerTest {

    private final AtomicInteger cleanups = new AtomicInteger();

    @Test
    @DisplayName("[H-4] 작업 안에서 실패하면 롤백이 확실하므로 정리한다")
    void cleansUpWhenWorkFails() {
        TransactionRunner tx = new TransactionRunner(new Manager(false));

        assertThatThrownBy(() -> tx.writeReadCommitted(() -> {
            throw new IllegalStateException("작업 실패");
        }, cleanups::incrementAndGet)).hasMessage("작업 실패");
        assertThat(cleanups).hasValue(1);
    }

    @Test
    @DisplayName("[H-4] 작업을 마친 뒤 커밋 단계에서 실패하면 커밋됐는지 모르므로 정리하지 않는다")
    void keepsFilesWhenCommitOutcomeUnknown() {
        TransactionRunner tx = new TransactionRunner(new Manager(true));

        assertThatThrownBy(() -> tx.writeReadCommitted(() -> "결과", cleanups::incrementAndGet))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(cleanups).hasValue(0);
    }

    @Test
    @DisplayName("성공하면 정리하지 않고 결과를 돌려준다")
    void returnsResult() {
        assertThat(new TransactionRunner(new Manager(false)).writeReadCommitted(() -> "결과", cleanups::incrementAndGet)).isEqualTo("결과");
        assertThat(cleanups).hasValue(0);
    }

    /** 실제 DB 없이 트랜잭션 흐름만 — commitFails 면 커밋에서 예외 */
    private record Manager(boolean commitFails) implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (commitFails) throw new TransactionSystemException("커밋 확인 응답 없음");
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
