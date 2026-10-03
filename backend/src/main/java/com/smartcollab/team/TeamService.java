package com.smartcollab.team;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.chat.ChatMessageRepository;
import com.smartcollab.file.DriveCleanupService;
import com.smartcollab.file.DriveDtos;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.TeamRoot;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.notification.Notification;
import com.smartcollab.notification.NotificationService;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.user.DemoAccounts;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final InvitationRepository invitations;
    private final FolderRepository folders;
    private final ChatMessageRepository chatMessages;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final DriveCleanupService cleanup;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final DemoAccounts demoAccounts;
    private final AppProperties props;

    /**
     * 팀과 팀장 멤버십, 팀 루트 폴더를 함께 만듭니다. 팀마다 저장 한도를 받으므로, 한 사람이 팀장인 팀 수를 제한하고
     * 체험 계정은 새 팀을 만들 수 없습니다 [S-10]. 사용자 행을 먼저 잠가 동시 요청으로 상한을 넘지 않게 합니다.
     */
    @Transactional
    public TeamDtos.TeamSummary create(String name, Long userId) {
        User owner = users.lockById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        demoAccounts.forbidIfDemo(owner, "체험 계정은 새 팀을 만들 수 없습니다.");
        int max = props.quota().teamsPerUser();
        if (teams.countByOwnerId(userId) >= max) {
            throw ApiException.conflict("팀장으로 있는 팀은 " + max + "개까지 만들 수 있습니다. 쓰지 않는 팀을 삭제하거나 팀장을 넘기세요.");
        }
        return createTeam(name, owner);
    }

    /** 데모 데이터 전용: 체험 계정의 데모 팀을 만듭니다(체험 계정 제한·팀 수 상한 없이). */
    @Transactional
    public TeamDtos.TeamSummary createDemoTeam(String name, Long userId) {
        return createTeam(name, users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED)));
    }

    private TeamDtos.TeamSummary createTeam(String name, User owner) {
        Team team = teams.save(new Team(name.strip(), owner));
        TeamMember leader = members.save(TeamMember.leader(team, owner));
        Folder root = folders.save(Folder.teamRoot(team, owner));
        return new TeamDtos.TeamSummary(team.getId(), team.getName(), owner.getName(), 1, root.getId(),
                DriveDtos.PermissionsResponse.of(Access.of(leader)));
    }

    /** 내 팀 목록. 소속·팀장(1회) + 인원 수(1회) + 루트 폴더(1회) = 쿼리 3회 (팀 수와 무관). */
    @Transactional(readOnly = true)
    public List<TeamDtos.TeamSummary> myTeams(Long userId) {
        List<TeamMember> memberships = members.findMembershipsOf(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> teamIds = memberships.stream().map(m -> m.getTeam().getId()).toList();
        Map<Long, Long> counts = members.countMembers(teamIds).stream()
                .collect(Collectors.toMap(TeamSize::teamId, TeamSize::members));
        Map<Long, Long> roots = folders.findTeamRootIds(teamIds).stream()
                .collect(Collectors.toMap(TeamRoot::teamId, TeamRoot::folderId, (a, b) -> Math.min(a, b)));
        return memberships.stream()
                .map(m -> new TeamDtos.TeamSummary(m.getTeam().getId(), m.getTeam().getName(),
                        m.getTeam().getOwner().getName(), counts.getOrDefault(m.getTeam().getId(), 0L),
                        roots.get(m.getTeam().getId()), DriveDtos.PermissionsResponse.of(Access.of(m))))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDtos.TeamDetail detail(Long teamId, Long userId) {
        TeamMember me = accessPolicy.requireMember(teamId, userId);
        Team team = teams.findWithOwner(teamId).orElseThrow(() -> ApiException.notFound("팀"));
        Long rootId = folders.findTeamRoot(teamId).map(Folder::getId).orElse(null);
        List<TeamDtos.MemberResponse> list = members.findMembers(teamId).stream().map(TeamDtos.MemberResponse::of).toList();
        return new TeamDtos.TeamDetail(team.getId(), team.getName(), team.getOwner().getUsername(), rootId,
                DriveDtos.PermissionsResponse.of(Access.of(me)), list);
    }

    @Transactional
    public void invite(Long teamId, String inviteeUsername, Long userId) {
        TeamMember inviterMembership = accessPolicy.requireMember(teamId, userId);
        if (!inviterMembership.mayInvite()) {
            throw ApiException.forbidden("팀원 초대 권한이 없습니다.");
        }
        User invitee = users.findByUsername(inviteeUsername.strip())
                .filter(u -> !u.isSystem())
                .orElseThrow(() -> ApiException.notFound("초대할 사용자"));
        if (members.existsByTeamIdAndUserId(teamId, invitee.getId())) {
            throw ApiException.conflict("이미 팀에 속한 사용자입니다.");
        }
        if (invitations.existsByTeamIdAndInviteeIdAndStatus(teamId, invitee.getId(), Invitation.Status.PENDING)) {
            throw ApiException.conflict("이미 초대를 보내 응답을 기다리는 중입니다.");
        }
        Team team = inviterMembership.getTeam();
        User inviter = inviterMembership.getUser();
        Invitation invitation = invitations.save(new Invitation(team, inviter, invitee));
        notifications.notify(invitee, Notification.Type.TEAM_INVITE,
                inviter.getName() + "님이 '" + team.getName() + "' 팀에 초대했습니다.", invitation, team);
    }

    @Transactional
    public void respondToInvitation(Long invitationId, boolean accept, Long userId) {
        Invitation invitation = invitations.findDetailed(invitationId)
                .filter(i -> i.getInvitee().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("초대"));
        Team team = invitation.getTeam();
        User invitee = invitation.getInvitee();
        // 수락 시점에 초대한 사람이 아직 이 팀에서 초대할 수 있는지 다시 확인합니다. 내보낸 멤버·초대 권한을 회수한 멤버가
        // 미리 보낸 초대로 팀에 들어오는 것을 막습니다 [S-08]. 받은 사람은 거절로 초대를 정리할 수 있습니다.
        TeamMember inviter = accept ? members.findByTeamIdAndUserId(team.getId(), invitation.getInviter().getId())
                .filter(TeamMember::mayInvite)
                .orElseThrow(() -> ApiException.conflict("초대한 사람이 더 이상 이 팀에 초대할 수 없어 수락할 수 없습니다. 초대를 거절해 정리하세요."))
                : null;
        invitation.respond(accept ? Invitation.Status.ACCEPTED : Invitation.Status.REJECTED);
        if (accept) {
            if (!members.existsByTeamIdAndUserId(team.getId(), userId)) {
                members.save(TeamMember.invitedBy(inviter, invitee));
            }
            events.publishEvent(new RealtimeEvents.TeamChanged(team.getId(), RealtimeEvents.TeamChangeType.MEMBERS_CHANGED));
        }
        notifications.notify(invitation.getInviter(),
                accept ? Notification.Type.INVITE_ACCEPTED : Notification.Type.INVITE_REJECTED,
                invitee.getName() + "님이 '" + team.getName() + "' 팀 초대를 " + (accept ? "수락" : "거절") + "했습니다.",
                team);
    }

    @Transactional
    public void updatePermissions(Long teamId, Long memberId, TeamDtos.PermissionRequest req, Long userId) {
        accessPolicy.requireLeader(teamId, userId);
        TeamMember target = memberOf(teamId, memberId);
        if (target.isTeamLeader()) {
            throw ApiException.badRequest("팀장의 권한은 바꿀 수 없습니다.");
        }
        List<String> changes = new ArrayList<>();
        describe(changes, "편집", target.isCanEdit(), req.canEdit());
        describe(changes, "삭제", target.isCanDelete(), req.canDelete());
        describe(changes, "초대", target.isCanInvite(), req.canInvite());
        target.updatePermissions(req.canEdit(), req.canDelete(), req.canInvite());
        if (!changes.isEmpty()) {
            notifications.notify(target.getUser(), Notification.Type.PERMISSION_CHANGED,
                    "'" + target.getTeam().getName() + "' 팀 권한 변경: " + String.join(", ", changes), target.getTeam());
            events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.MEMBERS_CHANGED));
        }
    }

    @Transactional
    public void removeMember(Long teamId, Long memberId, Long userId) {
        accessPolicy.requireLeader(teamId, userId);
        TeamMember target = memberOf(teamId, memberId);
        if (target.isTeamLeader()) {
            throw ApiException.badRequest("팀장은 내보낼 수 없습니다.");
        }
        demoAccounts.forbidIfDemo(target.getUser(), "체험 계정은 팀에서 내보낼 수 없습니다.");
        Team team = target.getTeam();
        User removed = target.getUser();
        members.delete(target);
        // 알림에 팀 ID 를 담아, 그 팀 화면을 보고 있던 사용자를 화면에서 내보낼 수 있게 합니다.
        notifications.notify(removed, Notification.Type.REMOVED_FROM_TEAM, "'" + team.getName() + "' 팀에서 제외되었습니다.", team);
        events.publishEvent(new RealtimeEvents.MembershipRevoked(teamId, removed.getId()));
        events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.MEMBERS_CHANGED));
    }

    @Transactional
    public void leave(Long teamId, Long userId) {
        TeamMember me = accessPolicy.requireMember(teamId, userId);
        if (me.isTeamLeader()) {
            throw ApiException.badRequest("팀장은 팀을 나갈 수 없습니다. 팀장을 위임하거나 팀을 삭제하세요.");
        }
        demoAccounts.forbidIfDemo(me.getUser(), "체험 계정은 팀을 나갈 수 없습니다.");
        members.delete(me);
        events.publishEvent(new RealtimeEvents.MembershipRevoked(teamId, userId));
        events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.MEMBERS_CHANGED));
    }

    /** 팀장 위임. v1 은 memberId 가 다른 팀 소속인지 확인하지 않아, 남의 팀 멤버를 팀장으로 지정할 수 있었습니다. */
    @Transactional
    public void delegateLeadership(Long teamId, Long memberId, Long userId) {
        TeamMember current = accessPolicy.requireLeader(teamId, userId);
        demoAccounts.forbidIfDemo(current.getUser(), "체험 계정은 팀장을 넘길 수 없습니다.");
        TeamMember next = memberOf(teamId, memberId);
        if (next.getId().equals(current.getId())) {
            throw ApiException.badRequest("이미 팀장입니다.");
        }
        Team team = teams.findWithOwner(teamId).orElseThrow(() -> ApiException.notFound("팀"));
        current.demoteFromLeader();
        next.promoteToLeader();
        team.changeOwner(next.getUser());
        notifications.notify(next.getUser(), Notification.Type.LEADERSHIP_TRANSFERRED,
                "'" + team.getName() + "' 팀의 새 팀장이 되었습니다.", team);
        events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.MEMBERS_CHANGED));
    }

    /**
     * 팀 삭제: 채팅 → 초대 → 팀 스토리지(폴더·파일·버전·서명·공유 링크) → 멤버 → 팀 순으로 지웁니다.
     * 저장소의 실제 파일은 커밋 이후에 삭제됩니다.
     */
    @Transactional
    public void delete(Long teamId, Long userId) {
        TeamMember leader = accessPolicy.requireLeader(teamId, userId);
        demoAccounts.forbidIfDemo(leader.getUser(), "체험 계정은 팀을 삭제할 수 없습니다.");
        purgeTeam(teamId, userId);
    }

    /**
     * 팀과 팀 스토리지·채팅·초대·멤버를 지웁니다. <b>권한은 확인하지 않으므로</b> 호출하는 쪽이 확인해야 합니다
     * (팀 삭제 API, 데모 데이터 초기화). actorUserId(없으면 null)를 뺀 멤버에게 알림을 보냅니다.
     */
    @Transactional
    public void purgeTeam(Long teamId, Long actorUserId) {
        Team team = teams.findWithOwner(teamId).orElseThrow(() -> ApiException.notFound("팀"));
        String teamName = team.getName();
        List<User> others = members.findMembers(teamId).stream()
                .map(TeamMember::getUser)
                .filter(u -> !u.getId().equals(actorUserId))
                .toList();

        chatMessages.deleteByTeam(teamId);
        invitations.deleteByTeam(teamId);
        for (Folder root : folders.findTeamRoots(teamId)) {
            cleanup.deleteFolderTree(root.getId());
        }
        members.deleteByTeam(teamId);
        teams.deleteById(teamId);

        for (User u : others) {
            notifications.notify(users.getReferenceById(u.getId()), Notification.Type.TEAM_DELETED,
                    "'" + teamName + "' 팀이 삭제되었습니다.", null);
        }
        events.publishEvent(new RealtimeEvents.TeamChanged(teamId, RealtimeEvents.TeamChangeType.TEAM_DELETED));
    }

    private TeamMember memberOf(Long teamId, Long memberId) {
        return members.findById(memberId)
                .filter(m -> m.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("팀원"));
    }

    private static void describe(List<String> out, String label, boolean before, boolean after) {
        if (before != after) {
            out.add(label + (after ? " 권한 부여" : " 권한 회수"));
        }
    }
}
