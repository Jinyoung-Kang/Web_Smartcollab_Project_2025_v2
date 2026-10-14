package com.smartcollab.team;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [CAP-02] 한 계정이 속할 수 있는 팀은 6개(팀장·멤버 합계). 예전에는 팀장인 팀만 10개로 셌고 멤버로 들어가는 팀은 끝없이 늘릴 수 있었습니다.
 * 팀 만들기·초대·초대 수락에서 모두 확인하고, 수락은 받는 사람의 사용자 행을 먼저 잠가 동시 수락으로 넘지 않게 합니다.
 */
class TeamMembershipLimitTest extends IntegrationTest {

    @Autowired
    TeamService teamService;
    @Autowired
    TeamMemberRepository members;
    @Autowired
    TransactionTemplate tx;

    private int memberships(long userId) {
        return jdbc.queryForObject("select count(*) from team_members where user_id = ?", Integer.class, userId);
    }

    /** 팀장이 초대 → 받은 사람의 알림에서 초대 ID 를 찾음(수락은 하지 않음) */
    private long invite(Api.Session leader, Api.Session invitee, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", invitee.username), teamId).andExpect(status().isCreated());
        return ((Number) Api.read(invitee.get("/api/notifications"), "$.items[0].invitationId")).longValue();
    }

    private void join(Api.Session leader, Api.Session member, long teamId) throws Exception {
        member.post("/api/invitations/{id}/accept", invite(leader, member, teamId)).andExpect(status().isNoContent());
    }

    /** 팀장 3개 + 멤버 3개 = 6개 팀에 속한 사용자 */
    private Api.Session inSixTeams(String prefix) throws Exception {
        Api.Session user = api().signUp(prefix);
        for (int i = 1; i <= 3; i++) {
            user.postJson("/api/teams", Map.of("name", "내 팀 " + i)).andExpect(status().isCreated());
        }
        for (int i = 1; i <= 3; i++) {
            Api.Session other = api().signUp(prefix + "o" + i);
            join(other, user, other.createTeam("남의 팀 " + i)[0]);
        }
        return user;
    }

    @Test
    @DisplayName("[CAP-02] 팀장·멤버를 합쳐 6개 팀에 속하면 7번째 팀은 만들 수 없다(409)")
    void cannotCreateSeventhTeam() throws Exception {
        Api.Session user = inSixTeams("cap02c");

        user.postJson("/api/teams", Map.of("name", "일곱째")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("한 계정은 팀 6개까지 속할 수 있습니다(팀장·멤버 포함). 쓰지 않는 팀에서 나가거나 팀을 삭제하세요."));
        assertThat(memberships(user.userId)).isEqualTo(6);
    }

    @Test
    @DisplayName("[CAP-02] 이미 6개 팀에 속한 사람은 초대할 수 없고(409), 초대를 받은 뒤 6개가 되면 수락도 409 — 거절은 된다")
    void inviteAndAcceptRespectLimit() throws Exception {
        Api.Session full = inSixTeams("cap02i");
        Api.Session leader = api().signUp("cap02l");
        long team = leader.createTeam("초대하는 팀")[0];
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", full.username), team).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("초대할 사용자가 이미 팀 6개에 속해 있어 초대할 수 없습니다."));

        // 5개 팀에 속했을 때 받은 초대를, 6번째 팀에 들어간 뒤 수락
        Api.Session late = api().signUp("cap02a");
        for (int i = 1; i <= 5; i++) {
            late.postJson("/api/teams", Map.of("name", "팀 " + i)).andExpect(status().isCreated());
        }
        long pending = invite(leader, late, team);
        late.postJson("/api/teams", Map.of("name", "여섯째")).andExpect(status().isCreated());
        late.post("/api/invitations/{id}/accept", pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("이미 팀 6개에 속해 있어 초대를 수락할 수 없습니다. 다른 팀에서 나간 뒤 수락하거나 초대를 거절하세요."));
        late.post("/api/invitations/{id}/reject", pending).andExpect(status().isNoContent());
        assertThat(memberships(late.userId)).isEqualTo(6);
    }

    @Test
    @DisplayName("[CAP-02] 5개 팀에 속한 사람이 초대 두 개를 동시에 수락해도 6개를 넘지 않는다 — 사용자 행을 잠근 뒤 셈")
    void concurrentAcceptsStayWithinLimit() throws Exception {
        Api.Session user = api().signUp("cap02r");
        for (int i = 1; i <= 5; i++) {
            user.postJson("/api/teams", Map.of("name", "팀 " + i)).andExpect(status().isCreated());
        }
        Api.Session a = api().signUp("cap02ra");
        Api.Session b = api().signUp("cap02rb");
        long invA = invite(a, user, a.createTeam("A 팀")[0]);
        long invB = invite(b, user, b.createTeam("B 팀")[0]);

        Throwable second;
        try (TransactionRace race = new TransactionRace(tx)) {
            second = race.run(() -> {
                teamService.respondToInvitation(invA, true, user.userId);
                members.flush();
            }, () -> teamService.respondToInvitation(invB, true, user.userId));
        }

        assertThat(second).isInstanceOf(ApiException.class).hasMessageContaining("팀 6개");
        assertThat(memberships(user.userId)).isEqualTo(6);
    }
}
