package com.smartcollab.support;

import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 실행 순서를 결정적으로 재현합니다. 다른 트랜잭션이 change 를 실행해 잠금을 쥔 채 커밋하지 않고 기다리는 동안
 * action 을 보냅니다.
 * <ul>
 *   <li>action 이 잠그지 않으면 바로 진행해, 커밋되지 않은 변경 이전의 데이터로 판단한 결과가 나옵니다(수정 전 재현).</li>
 *   <li>action 이 같은 행을 잠그면 2초 동안 막혀 있으므로, 상대를 커밋시킨 뒤 최신 데이터로 판단한 결과가 나옵니다(수정 후).</li>
 * </ul>
 */
public final class TransactionRace implements AutoCloseable {

    private final TransactionTemplate tx;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    public TransactionRace(TransactionTemplate tx) {
        this.tx = tx;
    }

    /**
     * @param change 상대 트랜잭션 안에서 실행할 변경. 잠금을 쥐도록 쓰기를 DB 에 내보내야(flush) 합니다.
     * @return action 이 던진 예외(정상 종료면 null)
     */
    public Throwable run(Runnable change, Runnable action) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> other = pool.submit(() -> tx.executeWithoutResult(st -> {
            change.run();
            changed.countDown();
            await(release);
        }));
        assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> mine = pool.submit((Callable<Throwable>) () -> {
            try {
                action.run();
                return null;
            } catch (Throwable e) {
                return e;
            }
        });
        Throwable result;
        try {
            result = mine.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException waitingForLock) {
            release.countDown();
            result = mine.get(20, TimeUnit.SECONDS);
        }
        release.countDown();
        other.get(20, TimeUnit.SECONDS);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
