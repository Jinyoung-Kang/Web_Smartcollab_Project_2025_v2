package com.smartcollab.global.config;

import com.smartcollab.support.FreezableTcpProxy;
import com.smartcollab.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Azure 첫 배포에서 App Service 가 컨테이너를 바꾸는 순간 생긴 두 가지 장애를 DB 앞의 중계기로 재현합니다.
 * <ul>
 *   <li>[DEP-01] 쿼리를 보낸 뒤 연결이 소리 없이 끊기면(응답도 RST 도 없음) 소켓 읽기에 상한이 없어 그 스레드가 영원히 멈췄습니다.
 *       기동 시드가 10분 넘게 멈춰 readiness 가 503 으로 남았고, DB 에는 열린 트랜잭션이 없었습니다. 트랜잭션 시간 제한(30초)은
 *       DB 에 KILL QUERY 를 보내는 방식이라 DB 가 받지 못한 쿼리는 풀지 못합니다(아래 첫 시험).</li>
 *   <li>[DEP-02] 기동할 때 첫 DB 연결이 한 번 시간 초과로 실패하자 Flyway 가 다시 시도하지 않아 기동 전체가 실패했습니다.</li>
 * </ul>
 */
class DatabaseNetworkFailureTest extends IntegrationTest {

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    @DisplayName("[DEP-01] 원인 — 쿼리 시간 제한만으로는 끊긴 연결의 응답 대기가 풀리지 않는다")
    void queryTimeoutDoesNotEndBlackHoledRead() throws Exception {
        try (Fixture f = new Fixture("0")) {   // 소켓 읽기 상한 없음(드라이버 기본값)
            CompletableFuture<Object> query = f.queryAfterFreeze(1);

            Thread.sleep(5_000);   // 쿼리 시간 제한(1초)의 다섯 배

            assertThat(query).as("끊긴 연결에서 응답을 기다리는 쿼리").isNotDone();
        }
    }

    @Test
    @DisplayName("[DEP-01] 앱의 DB 설정은 소켓 읽기에 상한을 둔다 — 트랜잭션 시간 제한보다 길고 2분 이하")
    void socketTimeoutIsConfigured() {
        Map<String, String> props = Binder.get(environment)
                .bind("spring.datasource.hikari.data-source-properties", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
        long txTimeoutMs = ((AbstractPlatformTransactionManager) txManager).getDefaultTimeout() * 1000L;

        assertThat(props).containsKey("socketTimeout");
        assertThat(Long.parseLong(props.get("socketTimeout")))
                .as("정상 쿼리는 트랜잭션 시간 제한(%dms) 안에 끝나므로 그보다 길게", txTimeoutMs)
                .isGreaterThan(txTimeoutMs)
                .isLessThanOrEqualTo(Duration.ofMinutes(2).toMillis());
    }

    @Test
    @DisplayName("[DEP-01] 소켓 읽기 상한이 있으면 끊긴 연결의 쿼리는 그 시간에 실패한다 (멈추지 않음)")
    void socketTimeoutEndsBlackHoledRead() throws Exception {
        try (Fixture f = new Fixture("2000")) {   // 시험 시간을 줄이려 2초 — 운영 값은 위 시험이 확인
            long started = System.nanoTime();
            Object result = f.queryAfterFreeze(30).get(15, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(SQLException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
        }
    }

    @Test
    @DisplayName("[DEP-02] 기동할 때 DB 가 잠깐 닿지 않아도 Flyway 가 다시 시도해 이어 간다")
    void flywayRetriesUntilDatabaseIsReachable() throws Exception {
        URI db = jdbcUri();
        int port = FreezableTcpProxy.freePort();   // 아직 아무도 듣지 않음 = 닿지 않는 DB
        int retries = Binder.get(environment).bind("spring.flyway.connect-retries", Integer.class).orElse(0);
        Duration interval = Binder.get(environment).bind("spring.flyway.connect-retries-interval", Duration.class)
                .orElse(Duration.ofSeconds(120));
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();
        FreezableTcpProxy[] proxy = new FreezableTcpProxy[1];
        later.schedule(() -> proxy[0] = new FreezableTcpProxy(port, db.getHost(), db.getPort()), 3, TimeUnit.SECONDS);

        try (HikariDataSource pool = pool("127.0.0.1", port, null)) {
            Flyway flyway = Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                    .connectRetries(retries).connectRetriesInterval((int) interval.toSeconds()).load();

            assertThatNoException().as("connect-retries=%d", retries).isThrownBy(flyway::info);
        } finally {
            later.shutdownNow();
            if (proxy[0] != null) {
                proxy[0].close();
            }
        }
    }

    // ── 도우미 ──

    /** 이 앱의 커넥션 풀 설정(spring.datasource.hikari.*)으로 만든 풀 — 중계기를 거쳐 시험 DB 에 붙습니다. */
    private HikariDataSource pool(String host, int port, String socketTimeout) {
        URI db = jdbcUri();
        HikariDataSource pool = new HikariDataSource();
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
        pool.setJdbcUrl("jdbc:mysql://" + host + ":" + port + db.getPath() + "?" + db.getQuery());
        pool.setUsername(environment.getProperty("spring.datasource.username"));
        pool.setPassword(environment.getProperty("spring.datasource.password"));
        if (socketTimeout != null) {
            pool.addDataSourceProperty("socketTimeout", socketTimeout);
        }
        return pool;
    }

    private URI jdbcUri() {
        return URI.create(environment.getProperty("spring.datasource.url").substring("jdbc:".length()));
    }

    /** 중계기 → 풀 → 연결 하나. 닫을 때는 중계기를 먼저 닫아 멈춘 읽기를 풀어 줍니다(연결부터 닫으면 그 연결의 잠금을 기다리며 멈춤). */
    private final class Fixture implements AutoCloseable {
        final FreezableTcpProxy proxy;
        final HikariDataSource pool;
        final Connection connection;

        Fixture(String socketTimeout) throws Exception {
            URI db = jdbcUri();
            proxy = new FreezableTcpProxy(db.getHost(), db.getPort());
            pool = pool("127.0.0.1", proxy.port(), socketTimeout);
            connection = pool.getConnection();
        }

        /** 연결이 살아 있는 것을 확인한 뒤 끊고(freeze) 쿼리를 보냅니다. 결과는 성공 시 "ok", 실패 시 그 예외. */
        CompletableFuture<Object> queryAfterFreeze(int queryTimeoutSeconds) throws SQLException {
            Statement st = connection.createStatement();
            st.setQueryTimeout(queryTimeoutSeconds);
            st.executeQuery("SELECT 1").close();
            proxy.freeze();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    st.executeQuery("SELECT 1").close();
                    return "ok";
                } catch (SQLException e) {
                    return e;
                }
            }, Executors.newVirtualThreadPerTaskExecutor());
        }

        @Override
        public void close() throws Exception {
            proxy.close();
            pool.close();
        }
    }
}
