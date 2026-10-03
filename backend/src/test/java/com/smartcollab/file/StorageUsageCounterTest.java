package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-01] 저장 한도 확인이 업로드·저장마다 그 범위의 모든 버전을 합산했습니다(O(파일 수)). 출시 기준 QA 에서 파일 10,362개인 사용자의
 * 업로드가 382개인 사용자보다 3배 느렸고(51 vs 17ms), 부하 중 느린 쿼리 1위였습니다(평균 30,509행 검사). 사용자·팀 행의 사용량 집계
 * (stored_bytes)를 같은 트랜잭션에서 고치고, 한도 확인은 그 값을 읽습니다. 집계는 실제 합계와 늘 같아야 합니다.
 */
class StorageUsageCounterTest extends IntegrationTest {

    @Autowired
    StorageUsageReconciler reconciler;

    private long personalCounter(long userId) {
        return jdbc.queryForObject("select stored_bytes from users where user_id = ?", Long.class, userId);
    }

    private long teamCounter(long teamId) {
        return jdbc.queryForObject("select stored_bytes from teams where team_id = ?", Long.class, teamId);
    }

    private long personalSum(long userId) {
        return jdbc.queryForObject("""
                select coalesce(sum(v.size), 0) from file_versions v join files f on f.file_id = v.file_id
                join folders fo on fo.folder_id = f.folder_id where fo.team_id is null and fo.owner_id = ?""", Long.class, userId);
    }

    private long teamSum(long teamId) {
        return jdbc.queryForObject("""
                select coalesce(sum(v.size), 0) from file_versions v join files f on f.file_id = v.file_id
                join folders fo on fo.folder_id = f.folder_id where fo.team_id = ?""", Long.class, teamId);
    }

    @Test
    @DisplayName("[IMP-01] 한도 확인은 합산이 아니라 사용자 행의 사용량 집계를 쓴다")
    void quotaUsesCounter() throws Exception {
        Api.Session s = api().signUp("imp01q");
        s.uploadText(s.rootFolderId, "작은.txt", "abc");
        jdbc.update("update users set stored_bytes = ? where user_id = ?", 1024L * 1024 * 1024 - 5, s.userId);   // 1GB 한도의 5바이트 전

        s.upload(s.rootFolderId, "넘침.bin", new byte[10])
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        s.get("/api/files/usage").andExpect(jsonPath("$.storedBytes").value(1024L * 1024 * 1024 - 5));
    }

    @Test
    @DisplayName("[IMP-01] 업로드·텍스트 저장·복사·휴지통·영구 삭제·폴더 삭제 뒤에도 집계는 실제 합계와 같다 (개인·팀)")
    void counterMatchesSumAfterOperations() throws Exception {
        Api.Session s = api().signUp("imp01o");
        long folder = s.createFolder(s.rootFolderId, "폴더");
        long a = s.uploadText(folder, "a.txt", "첫 내용");
        s.upload(s.rootFolderId, "b.bin", new byte[3000]);
        long base = ((Number) Api.read(s.get("/api/files/{id}/content", a), "$.versionId")).longValue();
        s.putJson("/api/files/{id}/content", Map.of("content", "두 번째 내용입니다", "baseVersionId", base), a).andExpect(status().isOk());
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "folder", "id", folder)), "targetFolderId", s.rootFolderId))
                .andExpect(status().is2xxSuccessful());
        assertThat(personalCounter(s.userId)).isEqualTo(personalSum(s.userId)).isPositive();

        s.delete("/api/files/{id}", a).andExpect(status().isNoContent());
        assertThat(personalCounter(s.userId)).as("휴지통은 여전히 셈").isEqualTo(personalSum(s.userId));
        s.delete("/api/trash/{id}", a).andExpect(status().isNoContent());
        s.delete("/api/folders/{id}", folder).andExpect(status().isNoContent());
        s.delete("/api/trash").andExpect(status().is2xxSuccessful());
        assertThat(personalCounter(s.userId)).isEqualTo(personalSum(s.userId));

        long[] team = s.createTeam("집계 팀");
        long teamFolder = s.createFolder(team[1], "팀 폴더");
        s.upload(teamFolder, "t.bin", new byte[5000]);
        s.uploadText(team[1], "t.txt", "팀 문서");
        assertThat(teamCounter(team[0])).isEqualTo(teamSum(team[0])).isEqualTo(5000L + "팀 문서".getBytes().length);
        s.delete("/api/folders/{id}", teamFolder).andExpect(status().isNoContent());
        s.delete("/api/trash/folders/{id}", teamFolder).andExpect(status().isNoContent());
        assertThat(teamCounter(team[0])).isEqualTo(teamSum(team[0]));
        assertThat(personalCounter(s.userId)).as("팀 파일은 개인 사용량에 셈하지 않음").isEqualTo(personalSum(s.userId));
    }

    @Test
    @DisplayName("[IMP-01] 같은 사용자가 동시에 업로드해도 집계를 잃지 않는다")
    void concurrentUploadsKeepCounter() throws Exception {
        Api.Session s = api().signUp("imp01c");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> uploads = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                uploads.add(() -> s.upload(s.rootFolderId, "동시-" + n + ".bin", new byte[1000 + n]).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(uploads)) {
                assertThat(f.get()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(personalCounter(s.userId)).isEqualTo(personalSum(s.userId)).isEqualTo(16 * 1000L + 120);
    }

    @Test
    @DisplayName("[IMP-01] 정리 작업은 어긋난 집계를 실제 합계로 바로잡고 고친 수를 돌려준다")
    void reconcilerFixesDrift() throws Exception {
        Api.Session s = api().signUp("imp01r");
        s.upload(s.rootFolderId, "r.bin", new byte[777]);
        long[] team = s.createTeam("정리 팀");
        s.upload(team[1], "t.bin", new byte[333]);
        jdbc.update("update users set stored_bytes = 1 where user_id = ?", s.userId);
        jdbc.update("update teams set stored_bytes = 2 where team_id = ?", team[0]);

        int fixed = reconciler.reconcile();

        assertThat(fixed).isGreaterThanOrEqualTo(2);
        assertThat(personalCounter(s.userId)).isEqualTo(777L);
        assertThat(teamCounter(team[0])).isEqualTo(333L);
        assertThat(reconciler.reconcile()).as("한 번 고친 뒤에는 어긋남 없음").isZero();
    }
}
