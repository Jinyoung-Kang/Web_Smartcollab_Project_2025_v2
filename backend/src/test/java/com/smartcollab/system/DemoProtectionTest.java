package com.smartcollab.system;

import com.smartcollab.support.Api;
import com.smartcollab.support.LimitedStorageIntegrationTest;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [SEC-06] 데모 모드의 체험 계정 보호: 다른 방문자의 체험을 망가뜨리는 작업 차단, 작은 저장 한도, 주기적 초기화.
 */
class DemoProtectionTest extends LimitedStorageIntegrationTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    UserRepository users;

    @Autowired
    com.smartcollab.team.TeamService teamService;

    @Autowired
    com.smartcollab.team.TeamRepository teams;

    @Autowired
    com.smartcollab.user.AccountService accountService;

    /**
     * 체험 데이터는 DB 에 하나뿐이라, 다른 설정(표본 파일·비밀번호)의 시험 문맥이 먼저 만든 체험 데이터가 남아 있을 수 있습니다.
     * 이 문맥의 설정으로 다시 만들고 시작합니다.
     */
    @BeforeEach
    void freshDemoData() {
        seeder.reset();
    }

    private Api.Session demo(String username) {
        return api().login(username, DEMO_PASSWORD);
    }

    private long demoTeamId(Api.Session s) {
        List<Number> ids = Api.read(s.get("/api/teams"), "$[?(@.name == 'SmartCollab 데모 팀')].id");
        return ids.getFirst().longValue();
    }

    private long memberId(Api.Session s, long teamId, String username) {
        List<Number> ids = Api.read(s.get("/api/teams/{t}", teamId), "$.members[?(@.username == '" + username + "')].memberId");
        return ids.getFirst().longValue();
    }

    /**
     * [QA6-03] 일반 팀이 체험 계정을 초대하면, 체험 비밀번호는 공개되어 있어 누구나 그 계정으로 수락해 팀 파일을 보고 받을 수 있었습니다.
     * 수락한 뒤에는 체험 계정 보호(내보내기·나가기 금지) 때문에 팀장이 내보낼 수도 없어, 다음 초기화까지 팀 자료가 열려 있었습니다.
     */
    @Test
    @DisplayName("[QA6-03] 일반 팀은 체험 계정을 초대할 수 없고, 이미 들어와 있는 체험 계정은 일반 팀의 팀장이 내보낼 수 있다")
    void regularTeamCannotInviteDemoAccount() throws Exception {
        Api.Session leader = api().signUp("qa603");
        long team = leader.createTeam("qa603-team")[0];

        leader.postJson("/api/teams/{t}/invitations", Map.of("username", "demo2"), team)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("체험 계정")));

        // 이 수정 전에 들어온 체험 계정(남아 있는 데이터)은 일반 팀의 팀장이 내보낼 수 있어야 합니다
        long demo2 = users.findByUsername("demo2").orElseThrow().getId();
        jdbc.update("insert into team_members (team_id, user_id, is_team_leader, can_edit, can_delete, can_invite, joined_at) values (?, ?, 0, 1, 0, 0, ?)",
                team, demo2, java.sql.Timestamp.from(java.time.Instant.now()));
        leader.delete("/api/teams/{t}/members/{m}", team, memberId(leader, team, "demo2")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[ADM-11] 관리자는 체험 계정을 삭제할 수 없고(403), 목록에는 체험 계정과 체험 한도로 표시된다")
    void adminCannotDeleteDemoAccounts() throws Exception {
        Api.Session admin = api().loginAdmin();
        long demo1 = users.findByUsername("demo1").orElseThrow().getId();

        admin.delete("/api/admin/users/{id}", demo1).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("체험 계정")));

        String page = Api.body(admin.get("/api/admin/users?size=100"));
        List<Boolean> demoFlags = com.jayway.jsonpath.JsonPath.read(page, "$.items[?(@.username == 'demo1')].demo");
        List<Number> quotas = com.jayway.jsonpath.JsonPath.read(page, "$.items[?(@.username == 'demo1')].quotaBytes");
        assertThat(demoFlags).containsExactly(true);
        assertThat(quotas).extracting(Number::longValue).containsExactly(32L * 1024);
        assertThat(users.findByUsername("demo1")).isPresent();
    }

    @Test
    @DisplayName("[SEC-06] 체험 계정은 탈퇴·팀 삭제·팀장 위임·팀 나가기·체험 계정 내보내기를 할 수 없다")
    void destructiveActionsAreBlocked() throws Exception {
        Api.Session leader = demo("demo1");
        Api.Session member = demo("demo2");
        long team = demoTeamId(leader);
        long demo2Member = memberId(leader, team, "demo2");

        leader.postJson("/api/users/me/delete", Map.of("password", DEMO_PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("체험 계정")));
        leader.delete("/api/teams/{t}", team).andExpect(status().isForbidden());
        leader.post("/api/teams/{t}/leader/{m}", team, demo2Member).andExpect(status().isForbidden());
        leader.delete("/api/teams/{t}/members/{m}", team, demo2Member).andExpect(status().isForbidden());
        member.post("/api/teams/{t}/leave", team).andExpect(status().isForbidden());

        assertThat(users.existsByUsername("demo1")).isTrue();
        leader.get("/api/teams/{t}", team).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[S-10] 체험 계정은 새 팀을 만들 수 없다 — 팀마다 저장 한도를 받아 한도를 우회할 수 있었음")
    void demoAccountsCannotCreateTeams() throws Exception {
        demo("demo3").postJson("/api/teams", java.util.Map.of("name", "체험 팀")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[SEC-06] 체험 계정에는 일반 계정보다 작은 저장 한도가 적용된다")
    void demoQuotaApplies() throws Exception {
        Api.Session visitor = demo("demo3");
        visitor.upload(visitor.rootFolderId, "big.bin", new byte[40 * 1024])   // 일반 한도 64KB 안, 체험 한도 32KB 초과
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        Api.Session normal = api().signUp("notdemo");
        normal.upload(normal.rootFolderId, "big.bin", new byte[40 * 1024]).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[SEC-06] 초기화하면 체험 계정·팀·문서가 처음 상태로 돌아오고, 일반 사용자 데이터는 그대로다")
    void resetRestoresDemoData() throws Exception {
        Api.Session outsider = api().signUp("outsider");
        long outsiderFile = outsider.uploadText(outsider.rootFolderId, "내 파일.txt", "keep me");
        Api.Session leader = demo("demo1");
        long oldId = leader.userId;
        leader.uploadText(leader.rootFolderId, "방문자가 올린 파일.txt", "visitor");
        Number visitedRoot = Api.read(leader.get("/api/teams/{t}", demoTeamId(leader)), "$.rootFolderId");
        leader.createFolder(visitedRoot.longValue(), "방문자가 만든 폴더");   // 체험 계정은 새 팀을 만들 수 없어 데모 팀에 흔적을 남김 [S-10]

        seeder.reset();

        Api.Session fresh = demo("demo1");
        assertThat(fresh.userId).isNotEqualTo(oldId);
        String personal = Api.body(fresh.get("/api/folders/{id}", fresh.rootFolderId));
        assertThat(personal).contains("할 일.txt").doesNotContain("방문자가 올린 파일");
        String teams = Api.body(fresh.get("/api/teams"));
        assertThat(teams).contains("SmartCollab 데모 팀");
        long team = demoTeamId(fresh);
        Number teamRoot = Api.read(fresh.get("/api/teams/{t}", team), "$.rootFolderId");
        assertThat(Api.body(fresh.get("/api/folders/{id}", teamRoot.longValue()))).contains("기획").contains("회의록").contains("디자인")
                .doesNotContain("방문자가 만든 폴더");
        assertThat(Api.body(fresh.get("/api/teams/{t}", team))).contains("demo2").contains("demo3");
        outsider.get("/api/files/{id}/content", outsiderFile).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[H-3] 일반 사용자는 체험 계정에게 팀장을 넘길 수 없다 — 넘기면 매일 초기화가 그 팀(다른 사람 파일 포함)을 영구 삭제했음")
    void cannotDelegateLeadershipToDemoAccount() throws Exception {
        Api.Session owner = api().signUp("h3owner");
        long[] team = owner.createTeam("일반 팀");
        long ownerFile = owner.uploadText(team[1], "팀 문서.txt", "keep me");
        owner.postJson("/api/teams/{t}/invitations", Map.of("username", "demo2"), team[0]).andExpect(status().isCreated());
        Api.Session visitor = demo("demo2");
        long invitation = ((Number) Api.read(visitor.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        visitor.post("/api/invitations/{id}/accept", invitation).andExpect(status().isNoContent());

        owner.post("/api/teams/{t}/leader/{m}", team[0], memberId(owner, team[0], "demo2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("체험 계정")));

        seeder.reset();
        owner.get("/api/files/{id}/content", ownerFile).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[L-3] 초기화가 중간에 실패해 체험 계정 일부만 남아도, 다음 시드가 남은 것을 정리하고 다시 만든다 — 예전에는 '이미 사용 중'으로 기동이 실패")
    void seedRecoversFromPartialReset() throws Exception {
        long demo1 = users.findByUsername("demo1").orElseThrow().getId();
        for (Long team : teams.findIdsOwnedBy(demo1)) teamService.purgeTeam(team, null);
        accountService.purgeAccount(demo1);   // demo1 만 지워지고 demo2·demo3 은 남은 상태

        seeder.seed();

        assertThat(users.existsByUsername("demo1")).isTrue();
        assertThat(Api.body(demo("demo1").get("/api/teams"))).contains("SmartCollab 데모 팀");
        assertThat(Api.body(demo("demo1").get("/api/teams/{t}", demoTeamId(demo("demo1"))))).contains("demo2").contains("demo3");
    }
}
