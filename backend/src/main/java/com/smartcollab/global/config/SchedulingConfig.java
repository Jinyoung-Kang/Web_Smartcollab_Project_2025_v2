package com.smartcollab.global.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 예약 작업(@Scheduled) 전용 스케줄러 [M-2].
 * <p>{@code @EnableWebSocketMessageBroker} 가 브로커 스케줄러를 만들면 Boot 의 기본 스케줄러가 생기지 않아, 예약 작업 6개가 브로커 스레드
 * (CPU 수만큼, App Service B1 은 1개)에서 차례로 돌았습니다. 하루 작업(휴지통 비우기·사용량 집계·고아 파일 정리·체험 초기화)이 도는 동안
 * 1분 주기 세션 정리가 밀렸고, 그중 하나가 저장소 장애에 걸리면 모든 예약 작업이 함께 멈췄습니다.</p>
 * <p>스케줄러는 빈으로 두지 않고 예약 작업에만 넘깁니다. 빈(실행기이기도 함)으로 두었더니 Boot 가 기본 실행기(applicationTaskExecutor)를
 * 만들지 않았고, STOMP 채널이 이 스케줄러를 함께 써서 하루 작업이 실시간 전송을 막고 종료 때 채널마다 30초씩 기다렸습니다(M-2 후속).</p>
 * <p>스레드 3개: 05:00 의 두 작업이 겹쳐도 짧은 작업이 돌 자리가 남습니다. 종료할 때는 진행 중인 작업을 30초까지 기다립니다
 * (하루 작업은 잠금·멱등이라 중간에 끊겨도 다음 실행이 이어 받습니다).</p>
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    public SchedulingConfig() {
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 기다리는 것은 돌고 있는 작업뿐 — cron 의 다음 실행(지연 작업)까지 기다리면 종료가 매번 30초 걸렸습니다
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.initialize();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.destroy();
    }
}
