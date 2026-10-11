package com.smartcollab.perf;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [M-2] 비밀번호 확인(bcrypt, 요청마다 수십~수백 ms)이 DB 커넥션을 쥐지 않는지. 로그인·공유 비밀번호·가입은 인증 없이 부를 수 있어,
 * 커넥션을 쥔 채 bcrypt 를 돌리면 동시 요청이 풀(기본 10개)을 차지해 다른 API 가 커넥션을 기다리다 503 이 됩니다.
 * 비밀번호를 비교·해시하는 순간의 활성 커넥션 수를 기록합니다.
 */
@Import(PasswordCheckConnectionTest.ProbeConfig.class)
class PasswordCheckConnectionTest extends IntegrationTest {

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ConnectionProbeEncoder connectionProbeEncoder(DataSource dataSource) {
            return new ConnectionProbeEncoder(dataSource);
        }
    }

    @Autowired
    ConnectionProbeEncoder encoder;

    @Test
    @DisplayName("[M-2] 로그인은 비밀번호를 비교하는 동안 DB 커넥션을 쥐지 않는다")
    void loginDoesNotHoldConnection() throws Exception {
        Api.Session user = api().signUp("m2login");
        encoder.reset();
        api().login(user.username, Api.PASSWORD);   // 200 이 아니면 실패

        assertThat(encoder.checks()).isPositive();
        assertThat(encoder.holdingAtCheck()).isZero();
        assertThat(encoder.maxActiveAtCheck()).isZero();
    }

    @Test
    @DisplayName("[M-2] 공유 링크 비밀번호 확인은 비교하는 동안 DB 커넥션을 쥐지 않는다")
    void shareUnlockDoesNotHoldConnection() throws Exception {
        Api.Session owner = api().signUp("m2share");
        long file = owner.uploadText(owner.rootFolderId, "a.txt", "공유");
        String token = Api.read(owner.postJson("/api/files/{id}/share-links", Map.of("password", "share-pass-1"), file), "$.token");
        encoder.reset();
        api().perform(MockMvcRequestBuilders.post("/api/public/shares/{t}/unlock", token).with(Api.xsrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"share-pass-1\"}"))
                .andExpect(status().isOk());

        assertThat(encoder.checks()).isPositive();
        assertThat(encoder.holdingAtCheck()).isZero();
        assertThat(encoder.maxActiveAtCheck()).isZero();
    }

    @Test
    @DisplayName("[M-2] 가입은 비밀번호를 해시하는 동안 DB 커넥션을 쥐지 않는다")
    void signUpDoesNotHoldConnection() throws Exception {
        encoder.reset();
        api().signUp("m2signup");

        assertThat(encoder.checks()).isPositive();
        assertThat(encoder.holdingAtCheck()).isZero();
        assertThat(encoder.maxActiveAtCheck()).isZero();
    }

    @Test
    @DisplayName("[M-2] 측정: 20명이 동시에 로그인하는 동안 bcrypt 가 커넥션을 쥔 횟수와, 그사이 보낸 폴더 조회의 지연 (풀 10개)")
    void concurrentLogins() throws Exception {
        int users = 20;
        Api.Session reader = api().signUp("m2reader");
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < users; i++) names.add(api().signUp("m2c" + i).username);
        encoder.reset();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(users);
        long folderMs;
        long started = System.nanoTime();
        try {
            java.util.List<java.util.concurrent.Future<?>> logins = new java.util.ArrayList<>();
            for (String name : names) logins.add(pool.submit(() -> api().login(name, Api.PASSWORD)));
            long t = System.nanoTime();
            reader.get("/api/folders/{id}", reader.rootFolderId).andExpect(status().isOk());
            folderMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t);
            for (java.util.concurrent.Future<?> f : logins) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }
        long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.printf("[M-2] concurrentLogins=%d bcryptChecks=%d heldConnectionDuringBcrypt=%d folderListMsDuringLogins=%d totalMs=%d%n",
                users, encoder.checks(), encoder.holdingAtCheck(), folderMs, ms);
        assertThat(encoder.holdingAtCheck()).isZero();
    }

    /** 실제 BCrypt 에 들어가는 순간의 활성 커넥션 수를 기록 */
    static final class ConnectionProbeEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        private final DataSource dataSource;
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger checks = new AtomicInteger();
        /** bcrypt 에 들어간 스레드가 트랜잭션(=DB 커넥션)을 쥐고 있던 횟수 — 이 요청 자신이 쥐는지 */
        private final AtomicInteger holding = new AtomicInteger();

        ConnectionProbeEncoder(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void reset() {
            maxActive.set(0);
            checks.set(0);
            holding.set(0);
        }

        int holdingAtCheck() {
            return holding.get();
        }

        int maxActiveAtCheck() {
            return maxActive.get();
        }

        int checks() {
            return checks.get();
        }

        private void record() {
            try {
                int active = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
                maxActive.accumulateAndGet(active, Math::max);
                checks.incrementAndGet();
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                    holding.incrementAndGet();
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String encode(CharSequence rawPassword) {
            record();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            record();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
