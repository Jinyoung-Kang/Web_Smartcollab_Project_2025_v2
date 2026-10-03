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
 * [PERF-01·P-01~P-03] 저장소(Azure 등 네트워크) 입출력 동안 DB 커넥션·저장 공간 잠금을 붙잡는지 측정합니다.
 * 저장소 작업이 오래 걸리는 저장소로 바꿔 끼우고 다음을 잽니다.
 * <ul>
 *   <li>업로드·복사·텍스트 저장·텍스트 읽기·커밋 뒤 삭제가 저장소 작업 중 점유한 커넥션 수</li>
 *   <li>풀 크기만큼 업로드가 동시에 진행될 때 다른 사용자의 폴더 조회 지연</li>
 *   <li>텍스트 저장이 저장소에 쓰는 동안 같은 저장 공간의 폴더 만들기 지연, 휴지통 비우기 응답 시간</li>
 * </ul>
 */
@Import(StorageConnectionBenchmarkTest.SlowStorageConfig.class)
class StorageConnectionBenchmarkTest extends IntegrationTest {

    static final Duration STORAGE_DELAY = Duration.ofMillis(1500);
    static final Duration DELETE_DELAY = Duration.ofMillis(300);
    static final int TRASHED_FILES = 5;
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

    @Test
    @DisplayName("[P-01~P-03] 텍스트 저장·읽기와 커밋 뒤 저장소 삭제 중에는 DB 커넥션·저장 공간 잠금을 붙잡지 않는다")
    void textAndDeletionStorageIoDoNotHoldDb() throws Exception {
        Api.Session s = api().signUp("perftext");
        storage.putDelay = Duration.ZERO;   // 준비 단계는 빠르게
        long fileId = s.uploadText(s.rootFolderId, "memo.txt", "1판");
        long baseVersion = ((Number) Api.read(s.get("/api/files/{id}/content", fileId), "$.versionId")).longValue();
        List<Long> trashed = new ArrayList<>();
        for (int i = 0; i < TRASHED_FILES; i++) {
            long id = s.uploadText(s.rootFolderId, "trash-" + i + ".txt", "x");
            s.delete("/api/files/{id}", id).andExpect(status().is2xxSuccessful());
            trashed.add(id);
        }
        storage.putDelay = STORAGE_DELAY;

        // P-01: 텍스트 저장이 저장소에 쓰는 동안 같은 저장 공간에서 폴더 만들기
        storage.reset();
        CountDownLatch writing = storage.expectConcurrentPuts(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> save = pool.submit(() -> s.putJson("/api/files/{id}/content",
                Map.of("content", "2판", "baseVersionId", baseVersion), fileId).andExpect(status().isOk()));
        assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        s.postJson("/api/folders", Map.of("parentId", s.rootFolderId, "name", "저장 중 만든 폴더"))
                .andExpect(status().is2xxSuccessful());
        long folderCreateMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        save.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        int heldDuringTextSave = storage.held("put");

        // P-03: 텍스트 읽기
        storage.reset();
        storage.openDelay = STORAGE_DELAY;
        s.get("/api/files/{id}/content", fileId).andExpect(status().isOk());
        storage.openDelay = Duration.ZERO;
        int heldDuringTextRead = storage.held("open");

        // P-02: 휴지통 비우기 — 커밋 뒤 저장소 삭제(파일마다 지연)
        storage.reset();
        storage.deleteDelay = DELETE_DELAY;
        started = System.nanoTime();
        s.delete("/api/trash").andExpect(status().is2xxSuccessful());
        long emptyTrashMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(storage.awaitDeletes(TRASHED_FILES, Duration.ofSeconds(10))).as("커밋 뒤 저장소 삭제 완료").isTrue();
        storage.deleteDelay = Duration.ZERO;
        int heldDuringDelete = storage.held("delete");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("storageDelayMs", STORAGE_DELAY.toMillis());
        report.put("deleteDelayMsPerBlob", DELETE_DELAY.toMillis());
        report.put("trashedFiles", trashed.size());
        report.put("connectionsHeldDuringTextSaveStorageWrite", heldDuringTextSave);
        report.put("folderCreateLatencyMsWhileTextSaveWrites", folderCreateMs);
        report.put("connectionsHeldDuringTextReadStorageRead", heldDuringTextRead);
        report.put("connectionsHeldDuringPostCommitDelete", heldDuringDelete);
        report.put("emptyTrashResponseMs", emptyTrashMs);
        QueryCountBenchmarkTest.write("storage-transactions.json", report);

        assertThat(heldDuringTextSave).isZero();
        assertThat(folderCreateMs).isLessThan(STORAGE_DELAY.toMillis() / 2);
        assertThat(heldDuringTextRead).isZero();
        assertThat(heldDuringDelete).isZero();
        assertThat(emptyTrashMs).isLessThan(DELETE_DELAY.toMillis() * TRASHED_FILES / 2);
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

    /**
     * 저장소 작업을 일정 시간 지연시키고, 작업 중(지연이 끝난 시점) 활성 DB 커넥션 수를 작업별로 기록하는 저장소.
     * 지연이 끝난 시점에 재므로, 커밋 뒤 다른 스레드에서 지우는 경우에도 요청 트랜잭션의 커넥션이 남아 있는지 알 수 있습니다.
     */
    static class SlowBlobStorage implements BlobStorage {

        private final BlobStorage delegate;
        private final DataSource dataSource;
        private final Map<String, AtomicInteger> held = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger writing = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private volatile CountDownLatch concurrentPuts = new CountDownLatch(0);
        private volatile int expected;
        volatile Duration putDelay = STORAGE_DELAY;
        volatile Duration openDelay = Duration.ZERO;
        volatile Duration deleteDelay = Duration.ZERO;

        SlowBlobStorage(BlobStorage delegate, DataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
        }

        void reset() {
            held.clear();
            deletes.set(0);
        }

        int maxActiveAtEntry() {
            return held("put") + held("copy");
        }

        /** 해당 작업 중 관찰한 활성 커넥션 수의 최댓값 */
        int held(String operation) {
            AtomicInteger n = held.get(operation);
            return n == null ? 0 : n.get();
        }

        boolean awaitDeletes(int count, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (deletes.get() < count) {
                if (System.nanoTime() > deadline) return false;
                Thread.sleep(20);
            }
            return true;
        }

        CountDownLatch expectConcurrentPuts(int count) {
            expected = count;
            concurrentPuts = new CountDownLatch(1);
            return concurrentPuts;
        }

        @Override
        public StoredBlob put(String key, InputStream content, long size) {
            if (writing.incrementAndGet() >= expected) concurrentPuts.countDown();
            try {
                pause(putDelay);
                record("put");
                return delegate.put(key, content, size);
            } finally {
                writing.decrementAndGet();
            }
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            pause(STORAGE_DELAY);
            record("copy");
            delegate.copy(sourceKey, targetKey);
        }

        @Override
        public InputStream open(String key) {
            pause(openDelay);
            record("open");
            return delegate.open(key);
        }

        @Override
        public void delete(String key) {
            pause(deleteDelay);
            record("delete");
            delegate.delete(key);
            deletes.incrementAndGet();
        }

        @Override
        public Optional<String> readOnlyUrl(String key, Duration ttl) {
            return delegate.readOnlyUrl(key, ttl);
        }

        @Override
        public String type() {
            return delegate.type();
        }

        private void record(String operation) {
            try {
                int active = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
                held.computeIfAbsent(operation, k -> new AtomicInteger()).accumulateAndGet(active, Math::max);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void pause(Duration delay) {
            if (delay.isZero()) return;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
