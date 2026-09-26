package com.smartcollab.team;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeamWorkflowTest extends IntegrationTest {

    private long invitationIdOf(Api.Session s) throws Exception {
        return ((Number) Api.read(s.get("/api/notifications"), "$.items[0].invitationId")).longValue();
    }

    private long join(Api.Session leader, Api.Session member, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        member.post("/api/invitations/{id}/accept", invitationIdOf(member)).andExpect(status().isNoContent());
        String detail = Api.body(member.get("/api/teams/{t}", teamId));
        java.util.List<Number> ids = com.jayway.jsonpath.JsonPath.read(detail,
                "$.members[?(@.username == '" + member.username + "')].memberId");
        return ids.getFirst().longValue();
    }

    @Test
    @DisplayName("팀 생성 → 초대 → 수락 → 팀 목록·권한·루트 폴더")
    void inviteAndJoin() throws Exception {
        Api.Session leader = api().signUp("leader");
        Api.Session member = api().signUp("member");
        long[] team = leader.createTeam("프로젝트 팀");
        join(leader, member, team[0]);

        member.get("/api/teams")
                .andExpect(jsonPath("$[0].name").value("프로젝트 팀"))
                .andExpect(jsonPath("$[0].memberCount").value(2))
                .andExpect(jsonPath("$[0].rootFolderId").value(team[1]))
                .andExpect(jsonPath("$[0].myPermissions.canEdit").value(true))
                .andExpect(jsonPath("$[0].myPermissions.canDelete").value(false));
        leader.get("/api/notifications").andExpect(jsonPath("$.items[0].type").value("INVITE_ACCEPTED"));
    }

    @Test
    @DisplayName("중복 초대·이미 멤버·처리된 초대 재응답은 409")
    void duplicateInvitations() throws Exception {
        Api.Session leader = api().signUp("dupl");
        Api.Session member = api().signUp("dupm");
        long[] team = leader.createTeam("팀");
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]).andExpect(status().isCreated());
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]).andExpect(status().isConflict());
        long inv = invitationIdOf(member);
        member.post("/api/invitations/{id}/accept", inv).andExpect(status().isNoContent());
        member.post("/api/invitations/{id}/reject", inv).andExpect(status().isConflict());
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("권한: 편집 권한을 회수하면 업로드 403, 삭제 권한이 없으면 남의 파일 삭제 403")
    void permissionsAreEnforced() throws Exception {
        Api.Session leader = api().signUp("perml");
        Api.Session member = api().signUp("permm");
        long[] team = leader.createTeam("권한 팀");
        long memberId = join(leader, member, team[0]);
        long leadersFile = leader.uploadText(team[1], "팀장 파일.txt", "x");

        member.delete("/api/files/{id}", leadersFile).andExpect(status().isForbidden());
        member.uploadText(team[1], "내 파일.txt", "mine");

        leader.putJson("/api/teams/{t}/members/{m}/permissions",
                Map.of("canEdit", false, "canDelete", false, "canInvite", false), team[0], memberId).andExpect(status().isNoContent());
        member.upload(team[1], "또.txt", "x".getBytes()).andExpect(status().isForbidden());
        member.get("/api/notifications").andExpect(jsonPath("$.items[0].type").value("PERMISSION_CHANGED"));

        leader.putJson("/api/teams/{t}/members/{m}/permissions",
                Map.of("canEdit", true, "canDelete", true, "canInvite", false), team[0], memberId);
        member.delete("/api/files/{id}", leadersFile).andExpect(status().isNoContent());
        member.get("/api/trash?teamId={t}", team[0]).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("팀장 위임 후 이전 팀장은 편집 권한만 남고, 새 팀장만 팀을 삭제할 수 있다")
    void delegateLeadership() throws Exception {
        Api.Session leader = api().signUp("dell");
        Api.Session member = api().signUp("delm");
        long[] team = leader.createTeam("위임 팀");
        long memberId = join(leader, member, team[0]);
        leader.post("/api/teams/{t}/leader/{m}", team[0], memberId).andExpect(status().isNoContent());
        leader.delete("/api/teams/{t}", team[0]).andExpect(status().isForbidden());
        member.get("/api/teams/{t}", team[0]).andExpect(jsonPath("$.ownerUsername").value(member.username))
                .andExpect(jsonPath("$.myPermissions.leader").value(true));
        leader.post("/api/teams/{t}/leave", team[0]).andExpect(status().isNoContent());
        member.post("/api/teams/{t}/leave", team[0]).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팀 삭제: 폴더·파일·채팅이 모두 지워지고 저장소 파일도 커밋 후 삭제된다")
    void deleteTeamCleansEverything() throws Exception {
        Api.Session leader = api().signUp("teamdel");
        Api.Session member = api().signUp("teamdelm");
        long[] team = leader.createTeam("삭제될 팀");
        join(leader, member, team[0]);
        long folder = leader.createFolder(team[1], "자료");
        long file = leader.uploadText(folder, "a.txt", "a");
        String key = jdbc.queryForObject("select stored_path from file_versions where file_id = ?", String.class, file);
        leader.postJson("/api/teams/{t}/messages", Map.of("content", "안녕하세요"), team[0]).andExpect(status().isCreated());

        leader.delete("/api/teams/{t}", team[0]).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from teams where team_id = ?", Integer.class, team[0])).isZero();
        assertThat(jdbc.queryForObject("select count(*) from folders where team_id = ?", Integer.class, team[0])).isZero();
        assertThat(Files.exists(storageRoot().resolve(key))).isFalse();
        member.get("/api/teams").andExpect(jsonPath("$", hasSize(0)));
        member.get("/api/notifications").andExpect(jsonPath("$.items[0].type").value("TEAM_DELETED"));
    }

    @Test
    @DisplayName("채팅: 커서 페이지네이션, 팀 파일 공유 메시지, 팀장만 기록 삭제")
    void chatPagination() throws Exception {
        Api.Session leader = api().signUp("chat");
        Api.Session member = api().signUp("chatm");
        long[] team = leader.createTeam("채팅 팀");
        join(leader, member, team[0]);
        for (int i = 1; i <= 5; i++) {
            leader.postJson("/api/teams/{t}/messages", Map.of("content", "메시지 " + i), team[0]);
        }
        String first = Api.body(member.get("/api/teams/{t}/messages?size=3", team[0]));
        assertThat(com.jayway.jsonpath.JsonPath.<Boolean>read(first, "$.hasMore")).isTrue();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(first, "$.messages[2].content")).isEqualTo("메시지 5");
        long oldest = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.messages[0].id")).longValue();
        member.get("/api/teams/{t}/messages?size=3&before={b}", team[0], oldest)
                .andExpect(jsonPath("$.messages", hasSize(2)))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.messages[0].sender.username").value(leader.username));

        long teamFile = leader.uploadText(team[1], "공유.txt", "x");
        member.postJson("/api/teams/{t}/messages", Map.of("fileId", teamFile), team[0])
                .andExpect(jsonPath("$.type").value("FILE_SHARE"))
                .andExpect(jsonPath("$.file.name").value("공유.txt"));
        long personal = member.uploadText(member.rootFolderId, "개인.txt", "x");
        member.postJson("/api/teams/{t}/messages", Map.of("fileId", personal), team[0]).andExpect(status().isNotFound());

        member.delete("/api/teams/{t}/messages", team[0]).andExpect(status().isForbidden());
        leader.delete("/api/teams/{t}/messages", team[0]).andExpect(status().isNoContent());
        member.get("/api/teams/{t}/messages", team[0]).andExpect(jsonPath("$.messages", hasSize(0)));
    }
}
