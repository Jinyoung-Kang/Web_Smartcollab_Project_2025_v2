package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [AI-13] 번역에 보낼 PDF 글자 — 쪽 순서, 글자가 거의 없거나 읽을 수 없는 PDF 는 원본으로(null), 글자 수 상한, 압축 폭탄 */
class PdfTextTest {

    @Test
    @DisplayName("[AI-13] 쪽 순서대로 글자를 뽑고 쪽 사이는 빈 줄 — 그림뿐인 쪽은 건너뜀")
    void extractsPagesInOrder() {
        byte[] pdf = PdfFixtures.pdf(new String[]{"Expert manual", "How to use (VC++)"}, new String[]{}, new String[]{"Revision history"});
        assertThat(PdfText.extract(pdf, 10_000)).isEqualTo("Expert manual\nHow to use (VC++)\n\nRevision history");
    }

    @Test
    @DisplayName("[AI-13] 절반 넘는 쪽에 글자가 없거나(스캔) PDF 가 아니거나 손상됐으면 null — 원본을 그대로 보냄")
    void returnsNullWhenThereIsNoUsableText() {
        assertThat(PdfText.extract(PdfFixtures.pdf(new String[]{}, new String[]{}), 10_000)).isNull();
        assertThat(PdfText.extract(PdfFixtures.pdf(new String[]{"p. 1"}, new String[]{}), 10_000)).isNull();   // 쪽 번호만 있는 그림 쪽
        assertThat(PdfText.extract("%PDF-1.4 테스트".getBytes(StandardCharsets.UTF_8), 10_000)).isNull();
        assertThat(PdfText.extract(new byte[]{1, 2, 3}, 10_000)).isNull();
    }

    @Test
    @DisplayName("[AI-13·H-1] 뽑은 글자가 상한을 넘으면 그 자리에서 멈추고 413")
    void tooManyCharsIs413() {
        byte[] pdf = PdfFixtures.pdf(new String[]{"first page has some words"}, new String[]{"second page has more words"});
        assertThatThrownBy(() -> PdfText.extract(pdf, 30))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @ParameterizedTest(name = "쪽 내용 배열={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("[AI-13] 압축 폭탄(약 500KB 가 500MB 로 풀림) — 상한(64MB)까지만 풀어 보고 원본을 보냄. 쪽 내용이 배열이면 PDFBox 가 통째로 풀어 힙을 다 썼음")
    void compressionBombIsNotInflated(boolean contentsArray) {
        byte[] pdf = PdfFixtures.bomb(500L * 1024 * 1024, contentsArray);
        assertThat(pdf.length).isLessThan(2 * 1024 * 1024);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        String text = PdfText.extract(pdf, 10_000);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("bomb(array=%s): text=%s allocated=%dMB %dms%n", contentsArray, text, allocated >> 20, ms);
        assertThat(text).as("풀어낸 크기가 상한을 넘으면 원본을 보냄").isNull();
        assertThat(allocated).isLessThan(PdfText.MAX_INFLATED_BYTES);

        // 상한 안에서 풀리는 크기(10MB)는 정상으로 읽음
        assertThat(PdfText.extract(PdfFixtures.bomb(10L * 1024 * 1024, contentsArray), 10_000)).isEqualTo("Bomb page with enough words");
    }

    @Test
    @DisplayName("[AI-13] 필터 두 개(16진 → Flate)로 감싼 압축 폭탄도 중간 결과까지 상한에 세어 멈춘다 — 상한 안이면 정상으로 읽음")
    void chainedFilterBombIsNotInflated() {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        byte[] pdf = PdfFixtures.bomb(500L * 1024 * 1024, true, true);
        long before = threads.getCurrentThreadAllocatedBytes();
        assertThat(PdfText.extract(pdf, 10_000)).isNull();
        assertThat(threads.getCurrentThreadAllocatedBytes() - before).isLessThan(PdfText.MAX_INFLATED_BYTES);

        assertThat(PdfText.extract(PdfFixtures.bomb(1024 * 1024, true, true), 10_000)).isEqualTo("Bomb page with enough words");
    }
}
