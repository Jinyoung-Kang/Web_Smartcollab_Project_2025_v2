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
        r.add("app.storage.type", () -> "local");
        r.add("app.storage.local-root", STORAGE_ROOT::toString);
        r.add("app.rate-limit.login-per-minute", () -> 1000);
        r.add("app.rate-limit.login-per-account-per-10-minutes", () -> 1000);
        r.add("app.rate-limit.share-password-per-link-per-10-minutes", () -> 1000);
        r.add("app.rate-limit.signup-per-hour", () -> 1000);
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

    protected static Path storageRoot() {
        return STORAGE_ROOT;
    }
}
