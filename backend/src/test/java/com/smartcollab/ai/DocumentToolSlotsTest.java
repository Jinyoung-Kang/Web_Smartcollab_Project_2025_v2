package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [M-1] 요약·번역 동시 실행 상한 — 사용자당 2건(429), 서버 전체 상한(503), 끝나면 돌려줌 */
class DocumentToolSlotsTest {

    private static DocumentToolSlots slots(int global) {
        return new DocumentToolSlots(new AiProperties("", "claude-haiku-5-5", "low", "medium", 300_000, DataSize.ofMegabytes(10),
                Duration.ofSeconds(120), Duration.ofMinutes(10), global));
    }

    @Test
    @DisplayName("[M-1] 서버 전체 상한을 넘으면 503, 자리를 돌려주면 다시 들어간다")
    void globalLimit() {
        DocumentToolSlots slots = slots(1);
        DocumentToolSlots.Slot first = slots.acquire(1L);

        assertThatThrownBy(() -> slots.acquire(2L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        first.close();
        slots.acquire(2L).close();
    }

    @Test
    @DisplayName("[M-1] 한 사용자는 동시에 2건까지(3번째 429), 다 끝나면 그 사용자 기록이 남지 않는다")
    void perUserLimitAndCleanup() {
        DocumentToolSlots slots = slots(8);
        DocumentToolSlots.Slot a = slots.acquire(7L);
        DocumentToolSlots.Slot b = slots.acquire(7L);

        assertThatThrownBy(() -> slots.acquire(7L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
        slots.acquire(8L).close();   // 다른 사용자는 됨
        a.close();
        b.close();
        assertThat(slots.runningUsers()).isZero();
    }
}
