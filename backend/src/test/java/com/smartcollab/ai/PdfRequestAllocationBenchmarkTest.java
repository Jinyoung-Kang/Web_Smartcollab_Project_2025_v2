package com.smartcollab.ai;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [M-1] 10MB PDF 요약 요청 하나(토큰 세기 + 요약)가 호출 스레드에서 할당하는 바이트 — PDF 의 base64 를 count·create 마다 만들던 것을
 * 한 번으로 줄인 효과를 잽니다(가짜 Anthropic 서버, 과금 없음). 결과는 표준 출력과 단언으로 남깁니다.
 */
class PdfRequestAllocationBenchmarkTest {

    private static final int PDF_BYTES = 10 * 1024 * 1024;
    private FakeAnthropicServer server;
    private ClaudeDocumentAi ai;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAnthropicServer();
        AiProperties props = new AiProperties("test-key", "claude-haiku-5-5", "low", "low", 300_000, DataSize.ofMegabytes(10),
                Duration.ofSeconds(30), Duration.ofSeconds(30), 8);
        ai = new ClaudeDocumentAi(props, ClaudeDocumentAi.client(props, server.baseUrl(), 0));
    }

    @AfterEach
    void tearDown() {
        ai.destroy();
        server.close();
    }

    @Test
    @DisplayName("[M-1] 10MB PDF 요약 1건의 호출 스레드 할당량")
    void allocationPerPdfSummary() {
        byte[] pdf = new byte[PDF_BYTES];
        new Random(1).nextBytes(pdf);
        warmUp(pdf);

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        run(pdf);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        System.out.printf("[M-1] pdfBytes=%d allocatedMB=%.1f ratio=%.2f%n", PDF_BYTES, allocated / 1024.0 / 1024.0, (double) allocated / PDF_BYTES);
        // 수정 전(base64 를 count·create 마다) 133.4MB(13.3배) → 한 번만 106.8MB(10.7배). 다시 늘면 실패
        assertThat(allocated).isLessThan(12L * PDF_BYTES);
    }

    private void warmUp(byte[] pdf) {
        run(pdf);
    }

    private void run(byte[] pdf) {
        server.reply(FakeAnthropicServer.Reply.json("{\"input_tokens\":1234}"));
        server.reply(FakeAnthropicServer.Reply.json(FakeAnthropicServer.message("end_turn",
                "{\"summary\":\"요약\",\"keyPoints\":[]}", 1234, 10, null)));
        AiDocument doc = AiDocument.pdf("보고서.pdf", pdf);
        ai.countSummaryTokens(doc);
        ai.summarize(doc);
    }
}
