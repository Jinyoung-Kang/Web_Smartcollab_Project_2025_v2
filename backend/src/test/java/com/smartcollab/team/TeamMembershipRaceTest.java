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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 출시 기준 QA — 팀 구성원이 바뀌는 요청이 겹칠 때의 경합 ({@link TransactionRace} 로 순서를 결정적으로 재현).
 * <ul>
 *   <li>[QA-01] 팀장 위임과 그 멤버의 나가기·내보내기가 겹치면 팀장이 없는 팀이 남았습니다. 소유자는 팀에서 빠져 볼 수 없는데도
 *   "팀장인 팀"으로 셈해져 탈퇴할 수 없고, 남은 멤버는 아무도 팀을 관리(초대·삭제·위임)할 수 없었습니다
 *   (QA 스택에서 동시 요청 10회 중 1회 발생, qa/results/concurrency.json).</li>
 *   <li>[QA-05] 같은 초대를 수락·거절하면 둘 다 성공으로 응답하고, 초대한 사람에게 수락·거절 알림이 모두 갔습니다.</li>
 * </ul>
 */
class TeamMembershipRaceTest extends IntegrationTest {

    @Autowired
    TeamRepository teams;
    @Autowired
    TeamMemberRepository members;
    @Autowired
    InvitationRepository invitations;
    @Autowired
    TeamService teamService;
    @Autowired
    TransactionTemplate tx;

    private record Fixture(Api.Session leader, Api.Session member, long teamId, long leaderMemberId, long memberId) {
    }

    private Fixture teamWithMember(String prefix) throws Exception {
        Api.Session leader = api().signUp(prefix + "l");
        Api.Session member = api().signUp(prefix + "m");
        long teamId = leader.createTeam(prefix + " 팀")[0];
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv).andExpect(status().isNoContent());
        long leaderMemberId = members.findByTeamIdAndUserId(teamId, leader.userId).orElseThrow().getId();
        long memberId = members.findByTeamIdAndUserId(teamId, member.userId).orElseThrow().getId();
        return new Fixture(leader, member, teamId, leaderMemberId, memberId);
    }

    /** 다른 트랜잭션이 팀장 위임(멤버 행·팀 행 변경)을 실행해 잠금을 쥔 채 커밋하지 않은 동안 action 을 보냅니다. */
    private Throwable whileDelegating(Fixture f, Runnable action) throws Exception {
        try (TransactionRace race = new TransactionRace(tx)) {
            return race.run(() -> {
                Team team = teams.findWithOwner(f.teamId()).orElseThrow();
                TeamMember current = members.findById(f.leaderMemberId()).orElseThrow();
                TeamMember next = members.findById(f.memberId()).orElseThrow();
                current.demoteFromLeader();
                next.promoteToLeader();
                team.changeOwner(next.getUser());
                members.flush();
            }, action);
        }
    }

    /** 팀 소유자가 팀장 표시를 가진 멤버이고, 팀장 표시는 한 명뿐이어야 합니다. */
    private void assertTeamHasItsOwnerAsOnlyLeader(long teamId) {
        Long owner = jdbc.queryForObject("select owner_id from teams where team_id = ?", Long.class, teamId);
        Integer ownerIsLeader = jdbc.queryForObject(
                "select count(*) from team_members where team_id = ? and user_id = ? and is_team_leader", Integer.class, teamId, owner);
        Integer leaders = jdbc.queryForObject("select count(*) from team_members where team_id = ? and is_team_leader", Integer.class, teamId);
        assertThat(ownerIsLeader).as("소유자가 팀장 표시를 가진 멤버").isEqualTo(1);
        assertThat(leaders).as("팀장 표시 수").isEqualTo(1);
    }

    @Test
    @DisplayName("[QA-01] 팀장으로 위임되는 중에 그 멤버가 팀을 나가도 팀장 없는 팀이 남지 않는다")
    void leaveWhileBeingPromotedKeepsALeader() throws Exception {
        Fixture f = teamWithMember("qa01l");

        Throwable result = whileDelegating(f, () -> teamService.leave(f.teamId(), f.member().userId));

        assertThat(result).as("위임이 먼저 커밋되면 새 팀장은 나갈 수 없음").isInstanceOf(ApiException.class);
        assertTeamHasItsOwnerAsOnlyLeader(f.teamId());
        // 새 팀장은 그대로 탈퇴를 막는 대신, 팀을 볼 수 있고 다시 위임·삭제할 수 있어야 합니다
        f.member().get("/api/teams/{t}", f.teamId()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[QA-01] 팀장 위임이 커밋되기 전에 같은 팀장이 그 멤버를 내보내도 팀장 없는 팀이 남지 않는다")
    void removeWhileBeingPromotedKeepsALeader() throws Exception {
        Fixture f = teamWithMember("qa01r");

        Throwable result = whileDelegating(f, () -> teamService.removeMember(f.teamId(), f.memberId(), f.leader().userId));

        assertThat(result).as("위임이 먼저 커밋되면 이전 팀장은 더 이상 내보낼 수 없음").isInstanceOf(ApiException.class);
        assertTeamHasItsOwnerAsOnlyLeader(f.teamId());
    }

    @Test
    @DisplayName("[QA-01] 멤버가 팀을 나가는 중에 그 멤버에게 위임해도 팀장 없는 팀이 남지 않는다 (회귀 방지)")
    void delegateWhileMemberLeavesKeepsALeader() throws Exception {
        Fixture f = teamWithMember("qa01d");

        Throwable result;
        try (TransactionRace race = new TransactionRace(tx)) {
            result = race.run(() -> {
                members.delete(members.findById(f.memberId()).orElseThrow());
                members.flush();
            }, () -> teamService.delegateLeadership(f.teamId(), f.memberId(), f.leader().userId));
        }

        assertThat(result).isNotNull();
        assertTeamHasItsOwnerAsOnlyLeader(f.teamId());
    }

    @Test
    @DisplayName("[QA-05] 같은 초대를 거절하는 중에 수락하면 수락은 409 이고, 거절만 반영된다 (둘 다 성공으로 응답하던 문제)")
    void acceptWhileRejectingConflicts() throws Exception {
        Api.Session leader = api().signUp("qa05l");
        Api.Session invitee = api().signUp("qa05i");
        long teamId = leader.createTeam("qa05 팀")[0];
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", invitee.username), teamId).andExpect(status().isCreated());
        long inv = ((Number) Api.read(invitee.get("/api/notifications"), "$.items[0].invitationId")).longValue();

        Throwable result;
        try (TransactionRace race = new TransactionRace(tx)) {
            result = race.run(() -> {
                invitations.findDetailed(inv).orElseThrow().respond(Invitation.Status.REJECTED);
                invitations.flush();
            }, () -> teamService.respondToInvitation(inv, true, invitee.userId));
        }

        assertThat(result).isInstanceOf(ApiException.class).hasMessageContaining("이미 처리된 초대");
        assertThat(jdbc.queryForObject("select status from invitations where invitation_id = ?", String.class, inv)).isEqualTo("REJECTED");
        assertThat(members.existsByTeamIdAndUserId(teamId, invitee.userId)).isFalse();
    }
}
