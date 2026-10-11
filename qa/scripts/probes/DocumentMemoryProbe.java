package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;

import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * [H-1] 문서 본문 추출·추출 요약의 시간과 힙 측정 (REVIEW_2026-10-12). 컴파일한 백엔드 클래스로 실행합니다 — qa/scripts/probes/run-document-memory.sh
 * 모드: docx-bomb(압축 30KB 남짓 → XML 20MB), text-2mb(편집기 한도만 한 텍스트), docx-front-bomb(본문 앞에 1GB 로 풀리는 항목)
 */
public class DocumentMemoryProbe {

    /** 서비스가 넘기는 글자 수 상한과 같은 값(편집기 한도 TEXT_EDIT_MAX_BYTES 기본값) */
    private static final int TEXT_LIMIT = 2 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        long t0 = System.nanoTime();
        String outcome;
        try {
            outcome = switch (mode) {
                case "docx-bomb" -> summarize(DocxText.extract(bodyBomb(), TEXT_LIMIT));
                case "docx-front-bomb" -> "chars=" + DocxText.extract(frontBomb(), TEXT_LIMIT).length();
                case "text-2mb" -> summarize("ab cd. ".repeat(2 * 1024 * 1024 / 7));
                default -> throw new IllegalArgumentException(mode);
            };
        } catch (ApiException e) {
            outcome = "rejected=" + e.code();
        } catch (OutOfMemoryError e) {
            outcome = "OOM";
        }
        long peak = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) peak += p.getPeakUsage().getUsed();
        }
        System.out.printf("mode=%s xmx=%dMB %s ms=%d heapPeakMB=%d%n", mode, Runtime.getRuntime().maxMemory() / 1024 / 1024,
                outcome, (System.nanoTime() - t0) / 1_000_000, peak / 1024 / 1024);
    }

    private static String summarize(String text) {
        ExtractiveSummarizer.Summary s = new ExtractiveSummarizer().summarize(text);
        return "chars=" + text.length() + " sentences=" + s.totalSentences();
    }

    private static final String HEAD = "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body><w:p><w:r><w:t>";
    private static final String TAIL = "</w:t></w:r></w:p></w:body></w:document>";

    /** 본문 XML 이 20MB 한도에 딱 맞게 "ab cd. " 를 되풀이 — 압축하면 수십 KB */
    static byte[] bodyBomb() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(HEAD.getBytes(StandardCharsets.UTF_8));
            byte[] unit = "ab cd. ".getBytes(StandardCharsets.UTF_8);
            long written = HEAD.length();
            while (written + unit.length + TAIL.length() < 20L * 1024 * 1024 - 400) {
                zip.write(unit);
                written += unit.length;
            }
            zip.write(TAIL.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 본문 앞에 1GB 로 풀리는 항목 — ZipInputStream 은 건너뛸 때도 끝까지 풂 */
    static byte[] frontBomb() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.setLevel(9);
            zip.putNextEntry(new ZipEntry("customXml/item1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 1024; i++) zip.write(zeros);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write((HEAD + "본문" + TAIL).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
