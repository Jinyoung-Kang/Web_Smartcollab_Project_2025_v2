package com.smartcollab.system;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.team.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * [M-4] 체험 데이터 만들기가 중간에 실패해도, 다음 시드(기동·초기화)가 처음부터 다시 만들어야 합니다.
 * Azure 첫 배포에서 기동 시드가 도중에 멈춘 뒤 다시 시작하자, 체험 계정 셋이 이미 있다는 이유로 시드를 건너뛰어 다음 05:00 초기화까지
 * 체험 데이터가 일부만 남았습니다(qa/results/deploy-2026-10-12).
 */
@TestPropertySource(properties = {
        "app.demo.enabled=true",
        "app.demo.password=" + DemoSeedRecoveryTest.PASSWORD,
        "app.demo.quota=5MB",
        "app.demo.sample-files=false",
})
class DemoSeedRecoveryTest extends IntegrationTest {

    static final String PASSWORD = "demo-pass-3";

    @Autowired
    DemoDataSeeder seeder;

    @MockitoSpyBean
    TeamService teamService;

    @Test
    @DisplayName("[M-4] 시드가 끝부분에서 실패하면 일부만 만든 체험 계정을 지워, 다음 시드가 빠진 것 없이 다시 만든다")
    void partialSeedIsDiscarded() throws Exception {
        seeder.reset();   // 다른 시험 문맥이 만든 체험 데이터를 이 설정으로 다시 만듦
        doThrow(new IllegalStateException("주입한 실패")).doCallRealMethod()
                .when(teamService).createDemoTeam(eq("디자인 리뷰"), anyLong());

        try {
            seeder.reset();   // 초기화의 시드가 마지막 단계 직전에 실패
        } catch (RuntimeException expected) {
            // 수정 전에는 그대로 올라옴
        }
        seeder.seedOnStartup();   // 다음 기동

        Api.Session editor = api().login("demo2", PASSWORD);
        List<String> teams = Api.read(editor.get("/api/teams"), "$[*].name");
        assertThat(teams).as("마지막 단계(디자인 리뷰 팀)까지 만들어졌는지").contains("디자인 리뷰");
    }
}
