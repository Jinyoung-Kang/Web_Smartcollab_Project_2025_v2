package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-05] 저장 직후 서버가 죽거나 커밋 뒤 삭제가 실패하면, DB 가 가리키지 않는 파일이 저장소에 남았습니다(출시 기준 QA:
 * 폴더 복사 도중 강제 종료로 150개). 매일 유예 시간(기본 24시간)보다 오래된 고아 파일만 지웁니다 — 막 올라오는 중인 파일은
 * 아직 DB 에 없으므로 유예 시간 안의 파일은 남겨 둡니다.
 */
class OrphanBlobCleanerTest extends IntegrationTest {

    @Autowired
    OrphanBlobCleaner cleaner;

    private static Path blob(String key, Duration age) throws Exception {
        Path path = storageRoot().resolve(key);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "orphan");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
        return path;
    }

    @Test
    @DisplayName("[IMP-05] 유예 시간보다 오래된 고아 파일·임시 파일만 지우고, DB 가 가리키는 파일과 새 고아는 남긴다")
    void deletesOnlyOldOrphans() throws Exception {
        Api.Session s = api().signUp("imp05");
        long fileId = s.uploadText(s.rootFolderId, "남을.txt", "referenced");
        String referencedKey = jdbc.queryForObject("select v.stored_path from files f join file_versions v on v.version_id = f.active_version_id where f.file_id = ?",
                String.class, fileId);
        Path referenced = storageRoot().resolve(referencedKey);
        Files.setLastModifiedTime(referenced, FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        Path oldOrphan = blob("files/" + UUID.randomUUID(), Duration.ofDays(2));
        Path oldVersionOrphan = blob("versions/" + UUID.randomUUID(), Duration.ofDays(2));
        Path oldTemp = blob("files/.upload-" + UUID.randomUUID() + ".tmp", Duration.ofDays(2));
        Path freshOrphan = blob("files/" + UUID.randomUUID(), Duration.ofMinutes(5));

        OrphanBlobCleaner.Result result = cleaner.clean();

        assertThat(Files.exists(oldOrphan)).isFalse();
        assertThat(Files.exists(oldVersionOrphan)).isFalse();
        assertThat(Files.exists(oldTemp)).isFalse();
        assertThat(Files.exists(referenced)).as("DB 가 가리키는 파일").isTrue();
        assertThat(Files.exists(freshOrphan)).as("유예 시간 안의 파일(올라오는 중일 수 있음)").isTrue();
        assertThat(result.deleted()).isGreaterThanOrEqualTo(3);
        s.get("/api/files/{id}/download", fileId).andReturn();
        assertThat(Files.readString(referenced)).isEqualTo("referenced");
    }
}
