package com.smartcollab.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 통합 테스트 공통: 실제 MySQL 8.4(Testcontainers) + Flyway 마이그레이션 + 로컬 디스크 저장소.
 * 컨테이너와 Spring 컨텍스트는 JVM 당 한 번만 띄우고, 테스트마다 고유한 사용자 이름을 써서 데이터가 섞이지 않게 합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    public static final String JWT_SECRET = "test-secret-test-secret-test-secret-1234";
    /** 관리 전용 계정 (ADR-0015) — 모든 통합 시험이 같은 컨텍스트를 쓰도록 공통 설정에 둡니다 */
    public static final String ADMIN_USERNAME = "it_admin";
    public static final String ADMIN_PASSWORD = "admin-password-for-tests";
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("smartcollab")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    static final Path STORAGE_ROOT;

    static {
        MYSQL.start();
        try {
            STORAGE_ROOT = Files.createTempDirectory("smartcollab-test-storage");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        r.add("app.jwt.secret", () -> JWT_SECRET);
        r.add("app.admin.username", () -> ADMIN_USERNAME);
        r.add("app.admin.password", () -> ADMIN_PASSWORD);
        r.add("app.storage.type", () -> "local");
        r.add("app.storage.local-root", STORAGE_ROOT::toString);
        r.add("app.rate-limit.login-per-minute", () -> 1000);
        r.add("app.rate-limit.login-per-account-per-10-minutes", () -> 1000);
        r.add("app.rate-limit.share-password-per-link-per-10-minutes", () -> 1000);
        r.add("app.rate-limit.signup-per-hour", () -> 1000);
        // 시험마다 계정을 새로 만들어 같은 DB 에 수백 개가 쌓입니다. 계정 수 상한은 AccountLimitTest 가 따로 봅니다 [CAP-03]
        r.add("app.quota.accounts", () -> 1_000_000);
        // 개발 PC 의 셸에 키가 있어도 시험이 실제 외부 API(과금)를 부르지 않게 비웁니다 — AI 는 가짜(DocumentAi)로 시험합니다 [AI-01]
        r.add("app.ai.api-key", () -> "");
        r.add("app.deepl.api-key", () -> "");
        // 같은 이유로 텔레그램 설정도 비웁니다 — 잠금을 켠 시험의 잠금 해제·차단이 운영자 폰으로 알림을 보내지 않게.
        // 알림은 가짜 전송부(GateNotifierTest·AccessGateFilterTest)로 시험합니다 [GATE-02]
        r.add("app.gate.telegram.bot-token", () -> "");
        r.add("app.gate.telegram.chat-id", () -> "");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api() {
        return new Api(mvc, json);
    }

    /** 커밋 뒤 저장소 삭제는 별도 스레드에서 일어나므로 [P-02], 저장소 파일이 사라질 때까지 잠시 기다립니다. */
    protected static boolean deletedFromStorage(String key) throws InterruptedException {
        Path file = STORAGE_ROOT.resolve(key);
        for (int i = 0; i < 100 && java.nio.file.Files.exists(file); i++) {
            Thread.sleep(50);
        }
        return !java.nio.file.Files.exists(file);
    }

    protected static Path storageRoot() {
        return STORAGE_ROOT;
    }
}
