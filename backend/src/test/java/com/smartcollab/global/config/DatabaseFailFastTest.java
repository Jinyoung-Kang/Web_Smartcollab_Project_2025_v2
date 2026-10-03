package com.smartcollab.global.config;

import com.smartcollab.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [QA-02] DB 에 연결할 수 없을 때(QA 스택에서 Toxiproxy 로 DB 연결을 끊음) 요청마다 30.3초를 기다린 뒤 500 으로 끝났습니다.
 * 커넥션 풀이 새 연결을 기다리는 시간이 HikariCP 기본값(30초) 그대로였기 때문입니다. 장애 중에도 사용자가 오래 붙잡히지 않도록
 * 이 앱의 설정(spring.datasource.hikari.*)으로 만든 풀이 몇 초 안에 실패해야 합니다.
 */
class DatabaseFailFastTest extends IntegrationTest {

    @Autowired
    Environment environment;

    @Test
    @DisplayName("[QA-02] DB 에 연결할 수 없으면 커넥션을 5초 안에 포기한다 (30초 동안 요청을 붙잡던 문제)")
    void unreachableDatabaseFailsFast() {
        try (HikariDataSource pool = new HikariDataSource()) {
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
            pool.setJdbcUrl("jdbc:mysql://127.0.0.1:1/unreachable");   // 아무도 듣지 않는 포트 = 끊긴 DB
            pool.setUsername("nobody");
            pool.setPassword("nothing");
            pool.setInitializationFailTimeout(-1);                       // 이미 떠 있던 앱의 풀처럼, 기동 확인 없이 연결을 기다리게

            long started = System.nanoTime();
            assertThatThrownBy(pool::getConnection).isInstanceOf(SQLException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(waited).as("연결을 기다린 시간").isLessThan(Duration.ofSeconds(6));
        }
    }
}
