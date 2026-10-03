package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [QA-06] 서로 다른 사용자가 동시에 업로드·텍스트 저장을 하면 MySQL 교착(1213)으로 500 이 났습니다
 * (QA 스택 시드 중 업로드 약 12,900건에서 45건, InnoDB 교착 보고서: 사용자 354·355 의 업로드).
 * 저장 한도 확인의 잠금 읽기(SUM … FOR SHARE)가 REPEATABLE READ 에서 인덱스 간격까지 잠가, 다른 사용자가 새 파일·버전 행을
 * 넣는 자리를 막았기 때문입니다. 두 업로드가 서로의 간격 잠금을 기다리면 교착이 됩니다.
 * <p>교착 자체는 시점에 달려 있어, 그 원인인 "다른 사용자의 한도 확인이 내 업로드를 막는다"를 결정적으로 재현합니다.</p>
 */
class UploadLockContentionTest extends IntegrationTest {

    @Autowired
    StorageQuota quota;
    @Autowired
    TransactionTemplate tx;

    @Test
    @DisplayName("[QA-06] 다른 사용자의 저장 한도 확인이 진행 중이어도 내 업로드는 기다리지 않는다 (서로 다른 사용자의 업로드가 교착되던 원인)")
    void otherUsersQuotaCheckDoesNotBlockMyUpload() throws Exception {
        Api.Session first = api().signUp("qa06a");
        first.uploadText(first.rootFolderId, "a.txt", "a");   // 첫 사용자의 파일 뒤 인덱스 간격이 잠기는 자리
        Api.Session second = api().signUp("qa06b");           // 폴더 ID 가 더 크고 아직 파일이 없어, 첫 업로드가 그 간격에 들어감

        AtomicReference<Duration> took = new AtomicReference<>();
        Throwable result;
        try (TransactionRace race = new TransactionRace(tx)) {
            result = race.run(
                    () -> quota.lockAndCheckRoom(StorageQuota.Scope.personal(first.userId), 0),   // 첫 사용자의 업로드가 한도를 확인하는 중
                    () -> {
                        long started = System.nanoTime();
                        try {
                            second.upload(second.rootFolderId, "b.txt", "b".getBytes()).andExpect(status().isCreated());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        took.set(Duration.ofNanos(System.nanoTime() - started));
                    });
        }

        assertThat(result).isNull();
        assertThat(took.get()).as("다른 사용자의 잠금을 기다린 시간").isLessThan(Duration.ofMillis(1500));
    }
}
