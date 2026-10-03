package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 휴지통에서 파일을 복원하는 동안 휴지통 비우기·자동 비우기가 겹칠 때 [3차 점검 독립 검토]. 이전에는 파일 복원이 잠그지 않고,
 * 비우기는 고른 ID 를 휴지통 여부를 다시 보지 않고 지워, 복원 응답(204)을 받은 파일이 영구 삭제될 수 있었습니다.
 */
class TrashConcurrencyTest extends IntegrationTest {

    @Autowired
    TrashService trashService;
    @Autowired
    FileRepository files;
    @Autowired
    PlatformTransactionManager txManager;

    private TransactionTemplate readCommitted() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }

    private long trashedFile(Api.Session s, String name) throws Exception {
        long id = s.uploadText(s.rootFolderId, name, "x");
        s.delete("/api/files/{id}", id);
        return id;
    }

    private Boolean deletedFlag(long fileId) {
        return jdbc.queryForList("select is_deleted from files where file_id = ?", Boolean.class, fileId).stream().findFirst().orElse(null);
    }

    @Test
    @DisplayName("휴지통 비우기와 겹쳐 복원한 파일은 지워지지 않는다")
    void emptyDoesNotDeleteFileBeingRestored() throws Exception {
        Api.Session s = api().signUp("trrestore");
        long file = trashedFile(s, "살릴 파일.txt");
        long other = trashedFile(s, "지울 파일.txt");

        try (TransactionRace race = new TransactionRace(readCommitted())) {
            race.run(() -> {
                trashService.restore(file, s.userId);
                files.flush();
            }, () -> trashService.empty(null, s.userId));
        }

        assertThat(deletedFlag(file)).as("복원된 파일이 남아 있음").isFalse();
        assertThat(deletedFlag(other)).as("휴지통에 있던 다른 파일은 지워짐").isNull();
    }

    @Test
    @DisplayName("자동 비우기와 겹쳐 복원한 파일은 지워지지 않는다")
    void purgeDoesNotDeleteFileBeingRestored() throws Exception {
        Api.Session s = api().signUp("trpurge");
        long file = trashedFile(s, "오래된 파일.txt");
        jdbc.update("update files set deleted_at = date_sub(utc_timestamp(6), interval 40 day) where file_id = ?", file);

        try (TransactionRace race = new TransactionRace(readCommitted())) {
            race.run(() -> {
                trashService.restore(file, s.userId);
                files.flush();
            }, () -> trashService.purgeExpired());
        }

        assertThat(deletedFlag(file)).as("복원된 파일이 남아 있음").isFalse();
    }
}
