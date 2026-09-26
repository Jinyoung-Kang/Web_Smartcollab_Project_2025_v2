package com.smartcollab.perf;

import com.smartcollab.storage.BlobStorage;
import com.smartcollab.storage.StoredBlob;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [PERF-01] 저장소(Azure 등 네트워크) 입출력 동안 DB 커넥션을 붙잡는지 측정합니다.
 * 저장소 쓰기·복사가 1.5초 걸리는 저장소로 바꿔 끼우고,
 * ① 업로드·복사가 저장소 작업 중 점유한 커넥션 수, ② 풀 크기만큼 업로드가 동시에 진행될 때 다른 사용자의 폴더 조회 지연을 잽니다.
 */
@Import(StorageConnectionBenchmarkTest.SlowStorageConfig.class)
class StorageConnectionBenchmarkTest extends IntegrationTest {

    static final Duration STORAGE_DELAY = Duration.ofMillis(1500);
    static final int POOL_SIZE = 10;   // application.yml 의 DB_POOL_SIZE 기본값

    @TestConfiguration
    static class SlowStorageConfig {
        @Bean
        @Primary
        SlowBlobStorage slowBlobStorage(@Qualifier("blobStorage") BlobStorage delegate, DataSource dataSource) {
            return new SlowBlobStorage(delegate, dataSource);
        }
    }

    @Autowired
    SlowBlobStorage storage;

    @Test
    @DisplayName("[PERF-01] 저장소 입출력 중에는 DB 커넥션을 붙잡지 않는다")
    void storageIoDoesNotHoldDbConnections() throws Exception {
        Api.Session s = api().signUp("perfconn");

        storage.reset();
        long fileId = Api.id(s.upload(s.rootFolderId, "one.bin", new byte[1024]));
        int heldDuringUpload = storage.maxActiveAtEntry();

        storage.reset();
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "file", "id", fileId)),
                "targetFolderId", s.rootFolderId)).andExpect(status().isOk());
        int heldDuringCopy = storage.maxActiveAtEntry();

        long contendedLatency = folderListLatencyWhileUploading(s);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("storageDelayMs", STORAGE_DELAY.toMillis());
        report.put("dbPoolSize", POOL_SIZE);
        report.put("connectionsHeldDuringUploadStorageWrite", heldDuringUpload);
        report.put("connectionsHeldDuringCopyStorageCopy", heldDuringCopy);
        report.put("folderListLatencyMsWhilePoolSizeUploadsWrite", contendedLatency);
        QueryCountBenchmarkTest.write("storage-connections.json", report);

        assertThat(heldDuringUpload).isZero();
        assertThat(heldDuringCopy).isZero();
        assertThat(contendedLatency).isLessThan(STORAGE_DELAY.toMillis() / 2);
    }

    /** 풀 크기만큼 업로드가 모두 저장소 쓰기 중일 때, 다른 사용자의 폴더 조회에 걸리는 시간(ms) */
    private long folderListLatencyWhileUploading(Api.Session uploader) throws Exception {
        Api.Session other = api().signUp("perfget");
        CountDownLatch allWriting = storage.expectConcurrentPuts(POOL_SIZE);
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        List<Future<?>> uploads = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            int n = i;
            uploads.add(pool.submit(() -> uploader.upload(uploader.rootFolderId, "parallel-" + n + ".bin", new byte[1024])
                    .andExpect(status().isCreated())));
        }
        assertThat(allWriting.await(10, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        other.get("/api/folders/{id}", other.rootFolderId).andExpect(status().isOk());
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        for (Future<?> f : uploads) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        return latencyMs;
    }

    /** 쓰기·복사를 일정 시간 지연시키고, 호출 시점의 활성 DB 커넥션 수를 기록하는 저장소 */
    static class SlowBlobStorage implements BlobStorage {

        private final BlobStorage delegate;
        private final DataSource dataSource;
        private final AtomicInteger maxActiveAtEntry = new AtomicInteger();
        private final AtomicInteger writing = new AtomicInteger();
        private volatile CountDownLatch concurrentPuts = new CountDownLatch(0);
        private volatile int expected;

        SlowBlobStorage(BlobStorage delegate, DataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
        }

        void reset() {
            maxActiveAtEntry.set(0);
        }

        int maxActiveAtEntry() {
            return maxActiveAtEntry.get();
        }

        CountDownLatch expectConcurrentPuts(int count) {
            expected = count;
            concurrentPuts = new CountDownLatch(1);
            return concurrentPuts;
        }

        @Override
        public StoredBlob put(String key, InputStream content, long size) {
            recordActiveConnections();
            if (writing.incrementAndGet() >= expected) concurrentPuts.countDown();
            try {
                pause();
                return delegate.put(key, content, size);
            } finally {
                writing.decrementAndGet();
            }
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            recordActiveConnections();
            pause();
            delegate.copy(sourceKey, targetKey);
        }

        @Override
        public InputStream open(String key) {
            return delegate.open(key);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public Optional<String> readOnlyUrl(String key, Duration ttl) {
            return delegate.readOnlyUrl(key, ttl);
        }

        @Override
        public String type() {
            return delegate.type();
        }

        private void recordActiveConnections() {
            try {
                int active = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
                maxActiveAtEntry.accumulateAndGet(active, Math::max);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void pause() {
            try {
                Thread.sleep(STORAGE_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
