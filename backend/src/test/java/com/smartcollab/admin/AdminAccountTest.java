package com.smartcollab.admin;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리 전용 계정 (ADR-0015). 관리자는 계정 목록(아이디·이름·소속 팀·사용량·가입일)을 보고 계정을 삭제만 하며,
 * 계정의 파일·채팅에는 닿지 못합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminAccountTest extends IntegrationTest {

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("[ADM-01] 설정한 관리자 계정으로 로그인하면 역할은 ADMIN 이고 드라이브가 없다")
    void adminAccountHasNoDrive() throws Exception {
        Api.Session admin = api().loginAdmin();

        admin.get("/api/auth/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.rootFolderId").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from folders where owner_id = ?", Integer.class, admin.userId)).isZero();
        Api.Session user = api().signUp("adm01");
        user.get("/api/auth/me").andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("[ADM-02] 관리자는 드라이브·팀·채팅·알림·검색 API 를 쓸 수 없다 (403)")
    void adminCannotUseUserFeatures() throws Exception {
        Api.Session owner = api().signUp("adm02");
        long file = owner.uploadText(owner.rootFolderId, "secret.txt", "관리자가 보면 안 되는 내용");
        long team = owner.createTeam("adm02-team")[0];
        Api.Session admin = api().loginAdmin();

        for (String path : List.of("/api/folders/" + owner.rootFolderId, "/api/folders/tree", "/api/files/" + file,
                "/api/files/" + file + "/download", "/api/files/search?q=secret", "/api/teams", "/api/teams/" + team,
                "/api/teams/" + team + "/messages", "/api/notifications", "/api/trash")) {
            admin.get(path).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        admin.postJson("/api/teams", Map.of("name", "admin-team")).andExpect(status().isForbidden());
        admin.postJson("/api/users/me/delete", Map.of("password", ADMIN_PASSWORD)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[ADM-03] 일반 사용자는 관리 API 를 쓸 수 없고(403), 로그인 전에는 401 이다")
    void usersCannotUseAdminApi() throws Exception {
        Api.Session user = api().signUp("adm03");
        Api.Session other = api().signUp("adm03b");

        user.get("/api/admin/users").andExpect(status().isForbidden());
        user.delete("/api/admin/users/{id}", other.userId).andExpect(status().isForbidden());
        api().perform(MockMvcRequestBuilders.get("/api/admin/users")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from users where user_id = ?", Integer.class, other.userId)).isOne();
    }

    @Test
    @DisplayName("[ADM-04] 계정 목록에는 아이디·이름·소속 팀·사용량·한도·가입일만 있고, 시스템·관리자 계정은 없다")
    void accountListShowsOnlySummaries() throws Exception {
        Api.Session leader = api().signUp("adm04l");
        Api.Session member = api().signUp("adm04m");
        long alpha = leader.createTeam("알파팀")[0];
        member.createTeam("베타팀");
        join(leader, member, alpha);
        byte[] bytes = "사용량 확인용 파일".getBytes(StandardCharsets.UTF_8);
        member.upload(member.rootFolderId, "usage.txt", bytes).andExpect(status().isCreated());
        Api.Session admin = api().loginAdmin();

        List<Map<String, Object>> rows = Api.read(admin.get("/api/admin/users?size=100"), "$.items");
        Map<String, Object> row = rows.stream().filter(r -> member.username.equals(r.get("username"))).findFirst().orElseThrow();

        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "username", "name", "teams", "storedBytes", "quotaBytes", "createdAt", "demo");
        assertThat(row).containsEntry("name", "adm04m님").containsEntry("storedBytes", bytes.length).containsEntry("demo", false);
        assertThat(((Number) row.get("quotaBytes")).longValue()).isPositive();
        assertThat(Instant.parse((String) row.get("createdAt"))).isBefore(Instant.now().plusSeconds(1));
        assertThat(row.get("teams")).isEqualTo(List.of(Map.of("name", "베타팀", "leader", true), Map.of("name", "알파팀", "leader", false)));
        // 가입일 최신순 — 방금 가입한 member 가 leader 보다 앞
        List<String> usernames = rows.stream().map(r -> (String) r.get("username")).toList();
        assertThat(usernames.indexOf(member.username)).isLessThan(usernames.indexOf(leader.username));

        List<String> all = allUsernames(admin);
        assertThat(all).contains(leader.username, member.username).doesNotContain(ADMIN_USERNAME, "deleted_user");
        String body = Api.body(admin.get("/api/admin/users?size=100"));
        assertThat(body).doesNotContain("usage.txt").doesNotContain("rootFolderId");
    }

    @Test
    @DisplayName("[ADM-05] 관리자가 계정을 삭제하면 개인 드라이브 파일이 지워지고 팀에서 빠지며, 그 계정의 토큰은 거절된다")
    void deletingAccountPurgesDriveAndMemberships(CapturedOutput output) throws Exception {
        Api.Session leader = api().signUp("adm05l");
        Api.Session leaving = api().signUp("adm05x");
        long team = leader.createTeam("adm05-team")[0];
        long teamRoot = ((Number) Api.read(leader.get("/api/teams/{t}", team), "$.rootFolderId")).longValue();
        join(leader, leaving, team);
        long personal = leaving.uploadText(leaving.rootFolderId, "mine.txt", "개인 파일");
        String personalKey = jdbc.queryForObject("select stored_path from file_versions where file_id = ?", String.class, personal);
        long teamFile = leaving.uploadText(teamRoot, "team.txt", "팀 자료");
        Api.Session admin = api().loginAdmin();

        admin.delete("/api/admin/users/{id}", leaving.userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredTeams").value(0))
                .andExpect(jsonPath("$.deletedTeams").value(0));

        assertThat(jdbc.queryForObject("select count(*) from users where user_id = ?", Integer.class, leaving.userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files where file_id = ?", Integer.class, personal)).isZero();
        assertThat(deletedFromStorage(personalKey)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from team_members where user_id = ?", Integer.class, leaving.userId)).isZero();
        // 팀 자료는 팀에 남고 작성자는 "탈퇴한 사용자"
        assertThat((String) Api.read(leader.get("/api/files/{id}", teamFile), "$.name")).isEqualTo("team.txt");
        leaving.get("/api/auth/me").andExpect(status().isUnauthorized());
        assertThat(output).contains("security-event account-deleted-by-admin admin=" + admin.userId + " user=" + leaving.userId);
    }

    @Test
    @DisplayName("[ADM-06] 팀장인 계정을 삭제하면 가장 먼저 들어온 멤버가 팀장이 되어 알림을 받고, 혼자인 팀은 삭제된다")
    void deletingLeaderHandsOverTeams() throws Exception {
        Api.Session leaving = api().signUp("adm06x");
        Api.Session first = api().signUp("adm06a");
        Api.Session second = api().signUp("adm06b");
        long shared = leaving.createTeam("adm06-shared")[0];
        long alone = leaving.createTeam("adm06-alone")[0];
        join(leaving, first, shared);
        join(leaving, second, shared);
        Api.Session admin = api().loginAdmin();

        admin.delete("/api/admin/users/{id}", leaving.userId).andExpect(status().isOk())
                .andExpect(jsonPath("$.transferredTeams").value(1))
                .andExpect(jsonPath("$.deletedTeams").value(1));

        List<Map<String, Object>> members = Api.read(first.get("/api/teams/{t}", shared), "$.members");
        assertThat(members).extracting(m -> m.get("username")).containsExactlyInAnyOrder(first.username, second.username);
        assertThat(members).filteredOn(m -> Boolean.TRUE.equals(m.get("leader"))).extracting(m -> m.get("username"))
                .containsExactly(first.username);
        assertThat((String) Api.read(first.get("/api/teams/{t}", shared), "$.ownerUsername")).isEqualTo(first.username);
        assertThat(Api.body(first.get("/api/notifications"))).contains("LEADERSHIP_TRANSFERRED").contains("adm06-shared");
        assertThat(jdbc.queryForObject("select count(*) from teams where team_id = ?", Integer.class, alone)).isZero();
    }

    @Test
    @DisplayName("[ADM-07][QA6-08] 시스템·관리자 계정과 없는 ID 는 삭제할 수 없고(404), 잘못된 page·size 는 400 이다(쪽 번호가 아주 커도 500 이 아님)")
    void invalidTargetsAndParameters() throws Exception {
        Api.Session admin = api().loginAdmin();
        long system = jdbc.queryForObject("select user_id from users where role = 'SYSTEM'", Long.class);

        admin.delete("/api/admin/users/{id}", system).andExpect(status().isNotFound());
        admin.delete("/api/admin/users/{id}", admin.userId).andExpect(status().isNotFound());
        admin.delete("/api/admin/users/{id}", Long.MAX_VALUE).andExpect(status().isNotFound());
        admin.get("/api/admin/users?size=0").andExpect(status().isBadRequest());
        admin.get("/api/admin/users?size=101").andExpect(status().isBadRequest());
        admin.get("/api/admin/users?page=-1").andExpect(status().isBadRequest());
        admin.get("/api/admin/users?page=abc").andExpect(status().isBadRequest());
        // [QA6-08] 쪽 번호 × 크기가 int 를 넘으면 Spring Data 가 예외(Page offset exceeds Integer.MAX_VALUE)를 던져 500 이었음
        admin.get("/api/admin/users?page=2147483647").andExpect(status().isBadRequest());
        admin.get("/api/admin/users?page=21474837&size=100").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from users where user_id in (?, ?)", Integer.class, system, admin.userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("[ADM-08] 관리자 계정은 팀에 초대할 수 없다 (404)")
    void adminCannotBeInvited() throws Exception {
        Api.Session leader = api().signUp("adm08");
        long team = leader.createTeam("adm08-team")[0];

        leader.postJson("/api/teams/{t}/invitations", Map.of("username", ADMIN_USERNAME), team).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[ADM-09] 설정과 다른 아이디의 관리자 계정(예전 관리자)은 로그인할 수 없다")
    void formerAdminCannotLogIn() throws Exception {
        String former = "old_admin_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into users (username, password, name, role, created_at) values (?, ?, '예전 관리자', 'ADMIN', ?)",
                former, passwordEncoder.encode(ADMIN_PASSWORD), Timestamp.from(Instant.now()));

        api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(Api.xsrf())
                        .contentType("application/json")
                        .content(api().toJson(Map.of("username", former, "password", ADMIN_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    private List<String> allUsernames(Api.Session admin) throws Exception {
        List<String> names = new ArrayList<>();
        int pages = Api.read(admin.get("/api/admin/users?size=100"), "$.totalPages");
        for (int p = 0; p < pages; p++) {
            List<String> page = Api.read(admin.get("/api/admin/users?size=100&page=" + p), "$.items[*].username");
            names.addAll(page);
        }
        return names;
    }

    /** 초대 → 수락 */
    private static void join(Api.Session leader, Api.Session member, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv).andExpect(status().isNoContent());
    }
}
