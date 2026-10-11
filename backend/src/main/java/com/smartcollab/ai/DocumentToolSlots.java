package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 요약·번역의 동시 실행 상한 [M-1]. 사용자당 {@value #PER_USER}건(넘으면 429), 서버 전체 AI_MAX_CONCURRENT 건(넘으면 503).
 * 문서 전체를 메모리에 올리고(PDF 는 base64 까지) 외부 API 를 오래 기다리는 작업이라, 겹쳐 보낸 요청이 그만큼 힙을 쓰고 과금되지 못하게 합니다.
 * 결과 캐시에 있는 요청은 자리를 쓰지 않습니다. 단일 인스턴스 전제(요청 제한기와 같음).
 */
@Component
class DocumentToolSlots {

    static final int PER_USER = 2;

    private final Semaphore global;
    /** 사용자별 진행 중인 수. 바꾸는 일은 compute 안에서만 하고, 0 이 되면 지워 메모리가 늘지 않게 합니다 */
    private final ConcurrentHashMap<Long, Integer> running = new ConcurrentHashMap<>();

    DocumentToolSlots(AiProperties props) {
        this.global = new Semaphore(Math.max(1, props.maxConcurrent()));
    }

    /** 자리를 하나 잡습니다. try-with-resources 로 닫으면 돌려줍니다 */
    Slot acquire(Long userId) {
        boolean[] taken = {false};
        running.compute(userId, (id, n) -> {
            int current = n == null ? 0 : n;
            if (current >= PER_USER) return n;
            taken[0] = true;
            return current + 1;
        });
        if (!taken[0]) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "진행 중인 요약·번역이 끝난 뒤 다시 시도하세요(한 사람당 동시에 " + PER_USER + "건).");
        }
        if (!global.tryAcquire()) {
            release(userId);
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "요약·번역 요청이 몰려 있습니다. 잠시 후 다시 시도하세요.");
        }
        return () -> {
            global.release();
            release(userId);
        };
    }

    private void release(Long userId) {
        running.compute(userId, (id, n) -> n == null || n <= 1 ? null : n - 1);
    }

    int runningUsers() {
        return running.size();
    }

    interface Slot extends AutoCloseable {
        @Override
        void close();
    }
}
