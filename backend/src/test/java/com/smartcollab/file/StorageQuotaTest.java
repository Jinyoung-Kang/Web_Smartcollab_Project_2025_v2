package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.LimitedStorageIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [SEC-05] 저장 공간 한도 (테스트 설정: 개인 64KB, 팀 128KB). 옛 버전·휴지통까지 실제 저장량으로 셉니다.
 */
class StorageQuotaTest extends LimitedStorageIntegrationTest {

    private static final int KB = 1024;

    @Test
    @DisplayName("[SEC-05] 개인 한도를 넘는 업로드는 413 QUOTA_EXCEEDED 이고 저장소에 파일을 남기지 않는다")
    void uploadOverQuotaIsRejected() throws Exception {
        Api.Session s = api().signUp("quota");
        s.upload(s.rootFolderId, "a.bin", new byte[40 * KB]).andExpect(status().isCreated());
        long blobs = storedBlobCount();

        s.upload(s.rootFolderId, "b.bin", new byte[30 * KB])
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        assertThat(storedBlobCount()).isEqualTo(blobs);
    }

    @Test
    @DisplayName("[SEC-05] 휴지통의 파일도 공간을 차지하고, 영구 삭제하면 공간이 돌아온다")
    void trashCountsUntilPurged() throws Exception {
        Api.Session s = api().signUp("quotatrash");
        long file = Api.id(s.upload(s.rootFolderId, "old.bin", new byte[40 * KB]));
        s.delete("/api/files/{id}", file).andExpect(status().isNoContent());
        s.upload(s.rootFolderId, "new.bin", new byte[30 * KB]).andExpect(status().isContentTooLarge());

        s.delete("/api/trash/{id}", file).andExpect(status().isNoContent());
        s.upload(s.rootFolderId, "new.bin", new byte[30 * KB]).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[SEC-05] 텍스트 새 버전도 공간을 차지해, 저장을 반복해 한도 없이 쌓을 수 없다")
    void textVersionsCount() throws Exception {
        Api.Session s = api().signUp("quotatext");
        String chunk = "a".repeat(30 * KB);
        long file = s.uploadText(s.rootFolderId, "memo.txt", chunk);
        Number v1 = Api.read(s.get("/api/files/{id}/content", file), "$.versionId");
        Number v2 = Api.read(s.putJson("/api/files/{id}/content", Map.of("content", chunk + "b", "baseVersionId", v1), file), "$.versionId");

        s.putJson("/api/files/{id}/content", Map.of("content", chunk + "c", "baseVersionId", v2), file)
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("[SEC-05] 복사도 대상 저장소의 한도를 확인한다")
    void copyChecksQuota() throws Exception {
        Api.Session s = api().signUp("quotacopy");
        long file = Api.id(s.upload(s.rootFolderId, "big.bin", new byte[40 * KB]));
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "file", "id", file)),
                        "targetFolderId", s.rootFolderId))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    @DisplayName("[SEC-05] 팀 저장소는 팀 한도를 따르고 개인 한도와 따로 센다")
    void teamQuotaIsSeparate() throws Exception {
        Api.Session s = api().signUp("quotateam");
        long[] team = s.createTeam("한도 팀");
        s.upload(s.rootFolderId, "personal.bin", new byte[50 * KB]).andExpect(status().isCreated());
        s.upload(team[1], "team-1.bin", new byte[100 * KB]).andExpect(status().isCreated());   // 개인 한도(64KB)보다 크지만 팀 한도 안
        s.upload(team[1], "team-2.bin", new byte[40 * KB]).andExpect(status().isContentTooLarge());
        s.get("/api/files/usage?teamId={t}", team[0])
                .andExpect(jsonPath("$.storedBytes").value(100 * KB))
                .andExpect(jsonPath("$.quotaBytes").value(128 * KB));
    }

    @Test
    @DisplayName("[SEC-05] 사용량 API 는 실제 저장량(옛 버전·휴지통 포함)과 한도를 알려 준다")
    void usageReportsStoredBytesAndQuota() throws Exception {
        Api.Session s = api().signUp("quotausage");
        long file = Api.id(s.upload(s.rootFolderId, "a.bin", new byte[10 * KB]));
        s.delete("/api/files/{id}", file);
        s.upload(s.rootFolderId, "b.bin", new byte[5 * KB]);
        s.get("/api/files/usage")
                .andExpect(jsonPath("$.fileCount").value(1))
                .andExpect(jsonPath("$.totalBytes").value(5 * KB))
                .andExpect(jsonPath("$.storedBytes").value(15 * KB))
                .andExpect(jsonPath("$.quotaBytes").value(64 * KB));
    }

    @Test
    @DisplayName("[SEC-05] 동시에 올려도 한도를 정확히 지킨다 (10KB × 8개 동시 → 64KB 한도에서 6개만)")
    void concurrentUploadsRespectQuota() throws Exception {
        Api.Session s = api().signUp("quotarace");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i;
            Callable<Integer> call = () -> s.upload(s.rootFolderId, "race-" + n + ".bin", new byte[10 * KB])
                    .andReturn().getResponse().getStatus();
            results.add(pool.submit(call));
        }
        int created = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 201) created++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(6);
    }

    private static long storedBlobCount() throws IOException {
        Path files = storageRoot().resolve("files");
        if (!Files.exists(files)) return 0;
        try (Stream<Path> walk = Files.list(files)) {
            return walk.filter(p -> !p.getFileName().toString().startsWith(".upload-")).count();
        }
    }
}
