package com.smartcollab.perf;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [M-6] 파일이 많은 계정의 탈퇴가 트랜잭션 시간 상한(기본 30초, DB_TX_TIMEOUT)에 닿는지 잽니다. 파일·버전 행은 SQL 로 바로 넣고
 * (저장소 파일은 없음 — 커밋 뒤 삭제는 없는 파일을 건너뜀) 탈퇴 API 의 응답 시간과 상태를 기록합니다.
 * 오래 걸려 CI 에서는 돌리지 않습니다: BENCH=true ./gradlew test --tests '*BulkDeletionBenchmarkTest'
 */
@EnabledIfEnvironmentVariable(named = "BENCH", matches = "true")
class BulkDeletionBenchmarkTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @ParameterizedTest(name = "파일 {0}개")
    @ValueSource(ints = {5_000, 20_000, 50_000})
    @DisplayName("[M-6] 파일 N개 계정의 탈퇴 시간")
    void accountDeletion(int files) throws Exception {
        Api.Session s = api().signUp("m6del" + files);
        insertFiles(s.userId, s.rootFolderId, files);

        long started = System.nanoTime();
        int status = s.postJson("/api/users/me/delete", Map.of("password", Api.PASSWORD)).andReturn().getResponse().getStatus();
        long ms = (System.nanoTime() - started) / 1_000_000;
        Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM files WHERE owner_id = ?", Integer.class, s.userId);
        System.out.printf("[M-6] files=%d status=%d ms=%d filesLeft=%d%n", files, status, ms, left);
    }

    private void insertFiles(long userId, long folderId, int count) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> fileRows = new ArrayList<>();
        for (int i = 0; i < count; i++) fileRows.add(new Object[]{folderId, userId, "f" + i + ".txt", 1, now, now});
        jdbc.batchUpdate("INSERT INTO files (folder_id, owner_id, original_name, size, is_deleted, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, false, ?, ?, 0)", fileRows);
        List<Long> ids = jdbc.queryForList("SELECT file_id FROM files WHERE owner_id = ? ORDER BY file_id", Long.class, userId);
        List<Object[]> versionRows = new ArrayList<>();
        for (Long id : ids) versionRows.add(new Object[]{id, "files/" + UUID.randomUUID(), userId, 1, "0".repeat(64), now});
        jdbc.batchUpdate("INSERT INTO file_versions (file_id, stored_path, editor_id, size, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)", versionRows);
        jdbc.update("UPDATE files f JOIN file_versions v ON v.file_id = f.file_id SET f.active_version_id = v.version_id WHERE f.owner_id = ?", userId);
        jdbc.update("UPDATE users SET stored_bytes = stored_bytes + ? WHERE user_id = ?", count, userId);
    }
}
