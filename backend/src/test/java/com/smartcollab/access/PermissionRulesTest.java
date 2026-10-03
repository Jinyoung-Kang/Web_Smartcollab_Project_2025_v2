package com.smartcollab.access;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [A-06] AccessPolicy 밖에 흩어져 있던 권한 규칙(팀 휴지통·서명·채팅 파일 공유)을 한 곳으로 모으기 전에 지금 동작을 고정합니다.
 */
class PermissionRulesTest extends IntegrationTest {

    /** leader 의 팀에 member 를 들이고 권한(편집 + 삭제 여부)을 정합니다. */
    private void join(Api.Session leader, Api.Session member, long teamId, boolean canDelete) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long invitation = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", invitation).andExpect(status().isNoContent());
        List<Number> ids = Api.read(leader.get("/api/teams/{t}", teamId), "$.members[?(@.username == '" + member.username + "')].memberId");
        leader.putJson("/api/teams/{t}/members/{m}/permissions",
                Map.of("canEdit", true, "canDelete", canDelete, "canInvite", false), teamId, ids.getFirst().longValue())
                .andExpect(status().isNoContent());
    }

    /** leader 의 새 팀에 member 를 들입니다. 팀 ID·팀 루트 폴더 ID 를 돌려줍니다. */
    private long[] teamWith(Api.Session leader, Api.Session member, boolean canDelete) throws Exception {
        long[] team = leader.createTeam("권한 팀");
        join(leader, member, team[0], canDelete);
        return team;
    }

    private static org.springframework.test.web.servlet.ResultActions teamTrash(Api.Session s, long teamId) {
        return s.send(MockMvcRequestBuilders.get("/api/trash").param("teamId", String.valueOf(teamId)));
    }

    @Test
    @DisplayName("[A-06] 팀 휴지통은 삭제 권한이 있는 멤버만 본다")
    void teamTrashNeedsDeletePermission() throws Exception {
        Api.Session leader = api().signUp("ptrashl");
        Api.Session viewer = api().signUp("ptrashv");
        Api.Session deleter = api().signUp("ptrashd");
        long[] team = teamWith(leader, viewer, false);
        join(leader, deleter, team[0], true);

        teamTrash(viewer, team[0])
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("팀 휴지통은 삭제 권한이 있는 멤버만 볼 수 있습니다."));
        teamTrash(deleter, team[0]).andExpect(status().isOk());
        teamTrash(api().signUp("ptrasho"), team[0]).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[A-06] 서명은 개인 파일이면 소유자, 팀 파일이면 팀장만 한다")
    void signingRules() throws Exception {
        Api.Session leader = api().signUp("psignl");
        Api.Session member = api().signUp("psignm");
        long[] team = teamWith(leader, member, true);
        long teamFile = member.uploadText(team[1], "팀 문서.txt", "x");
        member.post("/api/files/{id}/signatures", teamFile)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("팀 파일은 팀장만 서명할 수 있습니다."));
        leader.post("/api/files/{id}/signatures", teamFile).andExpect(status().is2xxSuccessful());

        long personal = member.uploadText(member.rootFolderId, "내 문서.txt", "x");
        member.post("/api/files/{id}/signatures", personal).andExpect(status().is2xxSuccessful());
        leader.post("/api/files/{id}/signatures", personal).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[A-06] 채팅에는 그 팀 스토리지의 휴지통이 아닌 파일만 공유한다")
    void chatFileShareRules() throws Exception {
        Api.Session leader = api().signUp("pchatl");
        Api.Session member = api().signUp("pchatm");
        long[] team = teamWith(leader, member, true);
        long[] other = leader.createTeam("다른 팀");
        long inTeam = leader.uploadText(team[1], "공유.txt", "x");
        long otherTeam = leader.uploadText(other[1], "다른 팀 파일.txt", "x");
        long personal = leader.uploadText(leader.rootFolderId, "개인.txt", "x");
        long trashed = leader.uploadText(team[1], "지운 파일.txt", "x");
        leader.delete("/api/files/{id}", trashed).andExpect(status().isNoContent());

        member.postJson("/api/teams/{t}/messages", Map.of("fileId", inTeam), team[0]).andExpect(status().isCreated());
        for (long notShareable : new long[]{otherTeam, personal, trashed}) {
            leader.postJson("/api/teams/{t}/messages", Map.of("fileId", notShareable), team[0])
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value(containsString("이 팀의 파일")));
        }
    }
}
