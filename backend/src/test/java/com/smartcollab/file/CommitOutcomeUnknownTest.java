package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [H-4] 커밋 결과를 알 수 없는 실패 — DB 에는 커밋됐는데 확인 응답을 받지 못한 경우(DB 장애 조치·네트워크 끊김).
 * 업로드·텍스트 저장·복사는 저장소에 먼저 쓰고 DB 에 기록하는데, 그 실패에서도 저장소 파일을 지워 "버전은 있는데 내용이 없는" 상태가 됐습니다.
 * 실제 커밋 뒤 한 번 예외를 던지는 트랜잭션 관리자로 재현합니다.
 */
@Import(CommitOutcomeUnknownTest.Config.class)
class CommitOutcomeUnknownTest extends IntegrationTest {

    @Autowired
    CommitThenFail commits;

    @Test
    @DisplayName("[H-4] 업로드의 커밋 확인이 실패해도, 커밋된 파일은 내려받을 수 있다")
    void uploadKeepsCommittedContent() throws Exception {
        Api.Session s = api().signUp("h4up");
        commits.failNextWriteAfterCommit();
        s.upload(s.rootFolderId, "보고서.txt", "커밋된 내용".getBytes())
                .andExpect(status().is5xxServerError());

        long file = fileNamed(s, "보고서.txt");   // 커밋은 됐으므로 목록에 있음
        s.send(MockMvcRequestBuilders.get("/api/files/{id}/download", file))
                .andExpect(status().isOk())
                .andExpect(content().string("커밋된 내용"));
    }

    @Test
    @DisplayName("[H-4] 텍스트 저장의 커밋 확인이 실패해도, 저장된 현재 버전의 내용을 읽을 수 있다")
    void saveKeepsCommittedContent() throws Exception {
        Api.Session s = api().signUp("h4save");
        long file = s.uploadText(s.rootFolderId, "메모.txt", "처음");
        long base = ((Number) Api.read(s.get("/api/files/{id}/content", file), "$.versionId")).longValue();
        commits.failNextWriteAfterCommit();
        s.putJson("/api/files/{id}/content", Map.of("content", "고친 내용", "baseVersionId", base), file)
                .andExpect(status().is5xxServerError());

        String current = Api.body(s.get("/api/files/{id}/content", file));
        assertThat(current).contains("고친 내용");
    }

    @Test
    @DisplayName("[H-4] 복사의 커밋 확인이 실패해도, 커밋된 복사본을 내려받을 수 있다")
    void copyKeepsCommittedContent() throws Exception {
        Api.Session s = api().signUp("h4copy");
        long file = s.uploadText(s.rootFolderId, "원본.txt", "복사할 내용");
        long target = s.createFolder(s.rootFolderId, "대상");
        commits.failNextWriteAfterCommit();
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "file", "id", file)), "targetFolderId", target))
                .andExpect(status().is5xxServerError());

        long copy = fileNamed(s, target, "원본.txt");
        s.send(MockMvcRequestBuilders.get("/api/files/{id}/download", copy))
                .andExpect(status().isOk())
                .andExpect(content().string("복사할 내용"));
    }

    private long fileNamed(Api.Session s, String name) throws Exception {
        return fileNamed(s, s.rootFolderId, name);
    }

    private long fileNamed(Api.Session s, long folderId, String name) throws Exception {
        List<Number> ids = Api.read(s.get("/api/folders/{id}", folderId), "$.items[?(@.name == '" + name + "')].id");
        assertThat(ids).as("커밋된 '%s' 가 목록에 있어야 함", name).hasSize(1);
        return ids.getFirst().longValue();
    }

    @TestConfiguration
    static class Config {
        /** 트랜잭션 관리자를 직접 두면 Boot 가 기본 JPA 관리자를 만들지 않으므로 같은 것을 만들어 감쌉니다 */
        @Bean(name = "transactionManager")   // Spring Data 저장소가 이 이름으로 찾음
        @Primary
        CommitThenFail commitThenFail(EntityManagerFactory entityManagerFactory) {
            return new CommitThenFail(new JpaTransactionManager(entityManagerFactory));
        }
    }

    /** 실제로 커밋한 뒤, 표시해 둔 다음 쓰기 트랜잭션 하나만 커밋 확인을 받지 못한 것처럼 예외를 던집니다 */
    static final class CommitThenFail implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private volatile boolean armed;
        private volatile TransactionStatus target;

        CommitThenFail(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        void failNextWriteAfterCommit() {
            armed = true;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            TransactionStatus status = delegate.getTransaction(definition);
            if (armed && target == null && definition != null && !definition.isReadOnly() && status.isNewTransaction()) {
                target = status;
            }
            return status;
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
            if (status == target) {
                target = null;
                armed = false;
                throw new TransactionSystemException("커밋 확인 응답을 받지 못함(시험)");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }
}
