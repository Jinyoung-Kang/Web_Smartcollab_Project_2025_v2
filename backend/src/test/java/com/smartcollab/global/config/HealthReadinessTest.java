package com.smartcollab.global.config;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-04] DB 연결이 끊긴 동안에도 readiness 가 200 이라, 부하 분산기가 요청을 계속 보냈습니다(QA db-cut).
 * readiness 그룹에 DB 상태를 넣어, DB 를 쓸 수 없으면 준비되지 않음(503)으로 알립니다. liveness 에는 넣지 않습니다(DB 장애로 앱을 재시작하지 않게).
 */
class HealthReadinessTest extends IntegrationTest {

    @Autowired
    HealthEndpointGroups groups;

    @Test
    @DisplayName("[IMP-04] readiness 는 DB 상태를 포함하고 liveness 는 포함하지 않는다")
    void readinessIncludesDatabase() {
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
    }
}
