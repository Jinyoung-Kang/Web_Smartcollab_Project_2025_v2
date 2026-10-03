package com.smartcollab.file;

import com.smartcollab.signature.SignatureService;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S-17] 문서 내용 변경(저장·버전 복원)과 서명이 겹칠 때. 서명은 현재 버전에 하고, 내용이 바뀌면 기존 서명을 무효로 합니다.
 * 이전에는 서명이 잠그지 않고 현재 버전을 읽어, 저장이 버전을 바꾸고 서명을 무효로 하는 사이에 끼어들면 밀려난 버전에
 * 유효한 서명이 남았습니다.
 */
class FileContentConcurrencyTest extends IntegrationTest {

    @Autowired
    FileContentService content;
    @Autowired
    SignatureService signatureService;
    @Autowired
    TransactionTemplate tx;

    private Long activeVersion(long fileId) {
        return jdbc.queryForObject("select active_version_id from files where file_id = ?", Long.class, fileId);
    }

    /** 유효한 서명이 붙은 버전들 */
    private List<Long> validlySignedVersions(long fileId) {
        return jdbc.queryForList("select version_id from signatures where file_id = ? and is_valid = true", Long.class, fileId);
    }

    @Test
    @DisplayName("[S-17] 새 버전 저장과 서명이 겹치면 서명은 새 버전에 붙고, 밀려난 버전에 유효한 서명이 남지 않는다")
    void signDuringSave() throws Exception {
        Api.Session s = api().signUp("signsave");
        long file = s.uploadText(s.rootFolderId, "계약.txt", "1판");
        long first = activeVersion(file);

        Throwable result;
        // 저장은 운영에서 자기 READ COMMITTED 트랜잭션으로 돌므로(저장 한도 확인이 그 격리 수준을 요구) 같은 격리 수준으로 붙듭니다
        TransactionTemplate readCommitted = new TransactionTemplate(tx.getTransactionManager());
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try (TransactionRace race = new TransactionRace(readCommitted)) {
            result = race.run(() -> content.saveText(file, "2판", first, s.userId),
                    () -> signatureService.sign(file, s.userId));
        }

        assertThat(result).isNull();
        assertThat(validlySignedVersions(file)).containsExactly(activeVersion(file)).doesNotContain(first);
    }

    @Test
    @DisplayName("[S-17] 버전 복원과 서명이 겹쳐도 서명은 복원된 현재 버전에 붙는다")
    void signDuringRestore() throws Exception {
        Api.Session s = api().signUp("signrestore");
        long file = s.uploadText(s.rootFolderId, "계약.txt", "1판");
        long first = activeVersion(file);
        s.putJson("/api/files/{id}/content", Map.of("content", "2판", "baseVersionId", first), file);
        long second = activeVersion(file);

        Throwable result;
        try (TransactionRace race = new TransactionRace(tx)) {
            result = race.run(() -> content.restore(file, first, s.userId),
                    () -> signatureService.sign(file, s.userId));
        }

        assertThat(result).isNull();
        assertThat(activeVersion(file)).isEqualTo(first);
        assertThat(validlySignedVersions(file)).containsExactly(first).doesNotContain(second);
    }
}
