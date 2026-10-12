package com.smartcollab.realtime;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M-2 후속] 예약 작업 전용 스케줄러를 빈으로 두자 Boot 가 기본 실행기(applicationTaskExecutor)를 만들지 않았고, STOMP 채널 실행기가
 * 그 스케줄러(스레드 3개)를 함께 썼습니다. 하루 작업이 도는 동안 실시간 전송이 밀리고, 종료 때 채널마다 30초씩 기다렸습니다.
 */
class MessagingExecutorTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("[M-2 후속] STOMP 채널(들어옴·나감·브로커)은 예약 작업 스케줄러와 다른 실행기에서 돈다")
    void messagingChannelsDoNotShareTheScheduler() {
        for (String name : new String[]{"clientInboundChannelExecutor", "clientOutboundChannelExecutor", "brokerChannelExecutor"}) {
            Executor executor = context.getBean(name, Executor.class);
            assertThat(executor).as(name).isNotInstanceOf(ThreadPoolTaskScheduler.class);
        }
    }
}
