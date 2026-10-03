package com.smartcollab.team;

import com.smartcollab.event.ChangeEvents;
import com.smartcollab.event.DeletionEvents;
import com.smartcollab.global.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 계정 삭제 때 팀 쪽 데이터(초대·멤버십)를 정리합니다 [A-02]. 지우는 쪽의 트랜잭션 안에서 동기로 실행됩니다. */
@Component
@RequiredArgsConstructor
class TeamAccountDeletionListener {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final InvitationRepository invitations;
    private final ApplicationEventPublisher events;

    /** 팀장인 팀이 남아 있으면 아무것도 지우기 전에 거절합니다(위임하거나 팀을 지운 뒤 탈퇴). */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_CHECK)
    void requireNoLedTeams(DeletionEvents.AccountDeleting event) {
        List<String> ledTeams = teams.findNamesOwnedBy(event.userId());
        if (!ledTeams.isEmpty()) {
            throw ApiException.conflict("팀장으로 있는 팀(" + String.join(", ", ledTeams)
                    + ")의 팀장을 위임하거나 팀을 삭제한 뒤 탈퇴할 수 있습니다.");
        }
    }

    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_INVITATIONS)
    void deleteInvitations(DeletionEvents.AccountDeleting event) {
        invitations.deleteByUser(event.userId());
    }

    /** 멤버십을 지우고, 빠진 팀의 열린 구독 해제·멤버 목록 갱신을 알립니다(커밋 뒤 전송). */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_MEMBERSHIPS)
    void deleteMemberships(DeletionEvents.AccountDeleting event) {
        List<Long> teamIds = members.findMembershipsOf(event.userId()).stream().map(m -> m.getTeam().getId()).toList();
        members.deleteByUser(event.userId());
        teamIds.forEach(teamId -> {
            events.publishEvent(new ChangeEvents.MembershipRevoked(teamId, event.userId()));
            events.publishEvent(new ChangeEvents.TeamChanged(teamId, ChangeEvents.TeamChangeType.MEMBERS_CHANGED));
        });
    }
}
