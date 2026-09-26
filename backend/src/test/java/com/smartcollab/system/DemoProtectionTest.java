package com.smartcollab.system;

import com.smartcollab.support.Api;
import com.smartcollab.support.LimitedStorageIntegrationTest;
import com.smartcollab.user.UserRepository;
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

    @Test
    @DisplayName("[SEC-06] 체험 계정은 탈퇴·팀 삭제·팀장 위임·팀 나가기·체험 계정 내보내기를 할 수 없다")
    void destructiveActionsAreBlocked() throws Exception {
        Api.Session leader = demo("demo1");
        Api.Session member = demo("demo2");
        long team = demoTeamId(leader);
        long demo2Member = memberId(leader, team, "demo2");

        leader.deleteJson("/api/users/me", Map.of("password", DEMO_PASSWORD))
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
        leader.createTeam("방문자가 만든 팀");

        seeder.reset();

        Api.Session fresh = demo("demo1");
        assertThat(fresh.userId).isNotEqualTo(oldId);
        String personal = Api.body(fresh.get("/api/folders/{id}", fresh.rootFolderId));
        assertThat(personal).contains("할 일.txt").doesNotContain("방문자가 올린 파일");
        String teams = Api.body(fresh.get("/api/teams"));
        assertThat(teams).contains("SmartCollab 데모 팀").doesNotContain("방문자가 만든 팀");
        long team = demoTeamId(fresh);
        Number teamRoot = Api.read(fresh.get("/api/teams/{t}", team), "$.rootFolderId");
        assertThat(Api.body(fresh.get("/api/folders/{id}", teamRoot.longValue()))).contains("기획").contains("회의록").contains("디자인");
        assertThat(Api.body(fresh.get("/api/teams/{t}", team))).contains("demo2").contains("demo3");
        outsider.get("/api/files/{id}/content", outsiderFile).andExpect(status().isOk());
    }
}
