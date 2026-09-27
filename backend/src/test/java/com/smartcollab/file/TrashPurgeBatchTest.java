package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [PERF-05] 보관 기간이 지난 휴지통 파일은 500개씩 따로 커밋하며 지웁니다. 이전에는 대상 전체를 한 트랜잭션으로 지워,
 * 대상이 많으면 잠금을 오래 쥐고 중간에 하나만 실패해도 전부 되돌아갔습니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TrashPurgeBatchTest extends IntegrationTest {

    @Autowired
    TrashService trashService;

    @Test
    @DisplayName("보관 기간이 지난 파일만 500개씩 나눠 영구 삭제한다")
    void purgesExpiredTrashInBatches(CapturedOutput output) {
        Api.Session s = api().signUp("purgebatch");
        Timestamp expired = Timestamp.from(Instant.now().minus(Duration.ofDays(40)));
        Timestamp recent = Timestamp.from(Instant.now().minus(Duration.ofDays(1)));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            rows.add(new Object[]{s.rootFolderId, s.userId, "old-" + i + ".txt", true, expired, expired, expired});
        }
        rows.add(new Object[]{s.rootFolderId, s.userId, "recent.txt", true, recent, recent, recent});
        rows.add(new Object[]{s.rootFolderId, s.userId, "active.txt", false, null, expired, expired});
        jdbc.batchUpdate("""
                insert into files (folder_id, owner_id, original_name, size, active_version_id, is_deleted, deleted_at,
                                   deleted_by, created_at, updated_at, version)
                values (?, ?, ?, 1, null, ?, ?, null, ?, ?, 0)""", rows);

        trashService.purgeExpired();

        List<String> left = jdbc.queryForList("select original_name from files where owner_id = ? order by original_name",
                String.class, s.userId);
        assertThat(left).containsExactly("active.txt", "recent.txt");
        Matcher m = Pattern.compile("Purged (\\d+) trashed files .* in (\\d+) batches").matcher(output.getOut());
        assertThat(m.find()).isTrue();
        int purged = Integer.parseInt(m.group(1));
        assertThat(purged).isGreaterThanOrEqualTo(1100);
        assertThat(Integer.parseInt(m.group(2))).isEqualTo((purged + TrashService.PURGE_BATCH - 1) / TrashService.PURGE_BATCH);
    }
}
