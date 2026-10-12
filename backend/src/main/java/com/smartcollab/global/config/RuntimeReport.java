package com.smartcollab.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동이 끝나면 JVM 이 보는 CPU 수·최대 힙·가상 스레드 사용 여부를 한 줄로 남깁니다.
 * App Service B1 에서 JVM 이 CPU 1개로 보고 가상 스레드 운반 스레드가 1개뿐이라 생긴 멈춤(C-1, ADR-0014)을 운영 로그만으로
 * 바로 판단하려고 둡니다.
 */
@Slf4j
@Component
public class RuntimeReport {

    private final boolean virtualThreads;

    public RuntimeReport(@Value("${spring.threads.virtual.enabled:false}") boolean virtualThreads) {
        this.virtualThreads = virtualThreads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Runtime runtime = Runtime.getRuntime();
        log.info(describe(runtime.availableProcessors(), runtime.maxMemory(), virtualThreads));
    }

    static String describe(int processors, long maxHeapBytes, boolean virtualThreads) {
        return "JVM runtime: availableProcessors=" + processors + ", maxHeap=" + (maxHeapBytes >> 20) + "MB, virtualThreads="
                + virtualThreads;
    }
}
