package com.smartcollab.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class RuntimeReportTest {

    @Test
    @DisplayName("[C-1] 기동 뒤 JVM 이 보는 CPU 수·최대 힙·가상 스레드 사용 여부를 한 줄로 남긴다 (운반 스레드 1개 문제를 로그로 판단)")
    void reportsProcessorsHeapAndVirtualThreads(CapturedOutput output) {
        assertThat(RuntimeReport.describe(1, 1024L << 20, false))
                .isEqualTo("JVM runtime: availableProcessors=1, maxHeap=1024MB, virtualThreads=false");

        new RuntimeReport(true).report();

        assertThat(output.getOut()).contains("JVM runtime: availableProcessors=" + Runtime.getRuntime().availableProcessors())
                .contains("virtualThreads=true");
    }
}
