package com.smartcollab.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 예약 작업(@Scheduled) 전용 스케줄러 [M-2].
 * <p>{@code @EnableWebSocketMessageBroker} 가 브로커 스케줄러를 만들면 Boot 의 기본 스케줄러가 생기지 않아, 예약 작업 6개가 브로커 스레드
 * (CPU 수만큼, App Service B1 은 1개)에서 차례로 돌았습니다. 하루 작업(휴지통 비우기·사용량 집계·고아 파일 정리·체험 초기화)이 도는 동안
 * 1분 주기 세션 정리가 밀렸고, 그중 하나가 저장소 장애에 걸리면 모든 예약 작업이 함께 멈췄습니다.
 * Spring 은 스케줄러가 여럿이면 이 이름({@code taskScheduler})의 빈을 예약 작업에 씁니다.</p>
 * <p>스레드 3개: 05:00 의 두 작업이 겹쳐도 짧은 작업이 돌 자리가 남습니다. 종료할 때는 진행 중인 작업을 30초까지 기다립니다
 * (하루 작업은 잠금·멱등이라 중간에 끊겨도 다음 실행이 이어 받습니다).</p>
 */
@Configuration
public class SchedulingConfig {

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
