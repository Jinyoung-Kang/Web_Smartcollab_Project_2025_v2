package com.smartcollab.global.config;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M-2] 예약 작업(@Scheduled)은 실시간 메시지(STOMP) 브로커의 스케줄러가 아니라 전용 스케줄러에서 돌아야 합니다.
 * {@code @EnableWebSocketMessageBroker} 가 만드는 브로커 스케줄러 때문에 Boot 의 기본 스케줄러가 생기지 않아, 6개 작업이 브로커 스레드
 * (CPU 수만큼, B1 은 1개)에서 차례로 돌았습니다 — 하루 작업이 도는 동안 1분 주기 세션 정리가 밀리고, 저장소 장애에 걸리면 모두 멈춤.
 */
@Import(SchedulingThreadTest.Probe.class)
class SchedulingThreadTest extends IntegrationTest {

    @Autowired
    Probe probe;

    @Test
    @DisplayName("[M-2] 예약 작업은 전용 스케줄러 스레드에서 돌고, 긴 작업이 도는 동안에도 다른 예약 작업이 돈다")
    void scheduledTasksRunOnDedicatedThreads() throws Exception {
        assertThat(probe.quickRan.await(10, TimeUnit.SECONDS)).as("긴 작업이 도는 동안 짧은 작업이 돌았는지").isTrue();
        assertThat(probe.threads).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith("scheduling-"));
    }

    @TestConfiguration
    static class Probe {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final CountDownLatch longStarted = new CountDownLatch(1);
        final CountDownLatch quickRan = new CountDownLatch(1);

        /** 하루 작업처럼 오래 걸리는 작업 */
        @Scheduled(fixedDelay = 60_000)
        void longJob() throws InterruptedException {
            threads.add(Thread.currentThread().getName());
            longStarted.countDown();
            Thread.sleep(Duration.ofSeconds(15));
        }

        /** 세션 정리처럼 짧고 자주 도는 작업 — 긴 작업이 시작된 뒤에 돈 것만 셈 */
        @Scheduled(fixedDelay = 200)
        void quickJob() {
            threads.add(Thread.currentThread().getName());
            if (longStarted.getCount() == 0) {
                quickRan.countDown();
            }
        }
    }
}
