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

    @MockitoSpyBean
    com.smartcollab.user.AccountService accountService;

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

    /**
     * [QA6-07] 초기화는 체험 계정이 이끄는 팀을 먼저 지우고(커밋) 계정을 지우는데, 계정 지우기가 실패하면(교착·일시적 DB 오류 — 방문자가 그 계정으로
     * 업로드 중이면 6차 QA 의 교착이 남) 다시 만들지 않고 끝났습니다. 계정 셋은 남아 있으므로 다음 기동의 시드도 "이미 있음"으로 건너뛰어,
     * 다음 날 초기화까지 체험 팀이 없는 공개 데모가 됐습니다.
     */
    @Test
    @DisplayName("[QA6-07] 초기화 중 체험 계정 지우기가 실패해도 체험 팀이 없는 채로 남지 않는다 — 다음 시드가 빠진 팀을 알아보고 다시 만든다")
    void failedPurgeDoesNotLeaveDemoWithoutTeams() throws Exception {
        seeder.reset();
        doThrow(new org.springframework.dao.CannotAcquireLockException("주입한 교착"))
                .doThrow(new org.springframework.dao.CannotAcquireLockException("주입한 교착"))
                .doCallRealMethod()
                .when(accountService).purgeAccount(anyLong());

        try {
            seeder.reset();
        } catch (RuntimeException ignored) {
            // 초기화 실패는 로그로만 남음
        }
        seeder.seedOnStartup();   // 다음 기동

        Api.Session editor = api().login("demo2", PASSWORD);
        List<String> teams = Api.read(editor.get("/api/teams"), "$[*].name");
        assertThat(teams).contains("SmartCollab 데모 팀", "디자인 리뷰");
    }
}
