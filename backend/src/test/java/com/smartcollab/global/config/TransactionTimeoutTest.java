package com.smartcollab.global.config;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [IMP-08] DB 가 느릴 때 요청 시간에 상한이 없었습니다(출시 기준 QA: 왕복마다 1초 지연에서 동시 30요청 p95 52.8초).
 * 모든 트랜잭션에 기본 시간 제한을 두고, 넘으면 MySQL 이 쿼리를 취소해 요청이 503 으로 끝나게 합니다.
 */
class TransactionTimeoutTest extends IntegrationTest {

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    @DisplayName("[IMP-08] 트랜잭션 기본 시간 제한은 30초다")
    void defaultTimeoutIsSet() {
        assertThat(((AbstractPlatformTransactionManager) txManager).getDefaultTimeout()).isEqualTo(30);
    }

    @Test
    @DisplayName("[IMP-08] 시간 제한을 넘는 쿼리는 DB 에서 취소되어 그 시간 즈음 실패한다")
    void slowQueryIsCancelled() {
        TransactionTemplate oneSecond = new TransactionTemplate(txManager);
        oneSecond.setTimeout(1);
        long started = System.nanoTime();

        assertThatThrownBy(() -> oneSecond.executeWithoutResult(st -> jdbc.queryForObject("SELECT SLEEP(10)", Integer.class)))
                .isInstanceOf(DataAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }
}
