package com.smartcollab.file;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [BUG-05] 휴지통 자동 비우기는 서버(컨테이너) 시간대와 무관하게 한국 시각 새벽 4시에 실행되어야 합니다.
 * 운영 컨테이너는 UTC 라서, 시간대를 지정하지 않으면 한국 시각 오후 1시에 실행됐습니다.
 */
class TrashPurgeScheduleTest extends IntegrationTest {

    @Autowired
    List<ScheduledTaskHolder> holders;

    @Test
    @DisplayName("[BUG-05] 휴지통 자동 비우기는 JVM 시간대와 무관하게 Asia/Seoul 04:00 에 실행된다")
    void purgeRunsAt4amSeoul() {
        CronTask task = holders.stream()
                .flatMap(h -> h.getScheduledTasks().stream())
                .map(scheduled -> scheduled.getTask())
                .filter(t -> t instanceof CronTask && t.toString().contains("TrashService.purgeExpired"))
                .map(CronTask.class::cast)
                .findFirst().orElseThrow();

        Instant from = Instant.parse("2026-01-01T00:00:00Z");   // 한국 시각 09:00
        Instant next = ((CronTrigger) task.getTrigger())
                .nextExecution(new SimpleTriggerContext(Clock.fixed(from, ZoneOffset.UTC)));

        assertThat(next).isEqualTo(Instant.parse("2026-01-01T19:00:00Z"));   // 한국 시각 1월 2일 04:00
    }
}
