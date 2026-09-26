package com.smartcollab.perf;

import com.smartcollab.storage.LocalBlobStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 다운로드 한 번에 서버 힙에 할당되는 바이트 측정 (스레드 할당량, com.sun.management.ThreadMXBean).
 * <ul>
 *   <li>v1 방식: 파일 전체를 ByteArrayOutputStream 에 받은 뒤 toByteArray() 로 다시 복사해 응답</li>
 *   <li>v2 방식: 저장소 스트림을 고정 크기 버퍼로 응답 스트림에 흘려보냄</li>
 * </ul>
 */
class DownloadMemoryBenchmarkTest {

    static final int SIZE = 32 * 1024 * 1024;

    @TempDir
    Path dir;

    @Test
    @DisplayName("32MB 다운로드 시 힙 할당량: 전체 버퍼링(v1) vs 스트리밍(v2)")
    void streamingAllocatesConstantMemory() throws Exception {
        LocalBlobStorage storage = new LocalBlobStorage(dir);
        byte[] data = new byte[SIZE];
        new Random(42).nextBytes(data);
        storage.put("files/big", new java.io.ByteArrayInputStream(data), data.length);
        data = null;

        // JIT 워밍업
        for (int i = 0; i < 3; i++) {
            legacy(storage);
            streaming(storage);
        }
        long legacyBytes = allocated(() -> legacy(storage));
        long streamingBytes = allocated(() -> streaming(storage));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("fileBytes", SIZE);
        report.put("v1BufferedAllocatedBytes", legacyBytes);
        report.put("v2StreamingAllocatedBytes", streamingBytes);
        QueryCountBenchmarkTest.write("download-memory.json", report);

        assertThat(legacyBytes).isGreaterThan(SIZE);
        assertThat(streamingBytes).isLessThan(1024 * 1024);
    }

    private static void legacy(LocalBlobStorage storage) {
        try (InputStream in = storage.open("files/big")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            byte[] body = buffer.toByteArray();
            OutputStream.nullOutputStream().write(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void streaming(LocalBlobStorage storage) {
        try (InputStream in = storage.open("files/big")) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long allocated(Runnable r) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        r.run();
        return mx.getThreadAllocatedBytes(id) - before;
    }
}
