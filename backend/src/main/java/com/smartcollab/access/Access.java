package com.smartcollab.access;

import com.smartcollab.team.TeamMember;

/**
 * 한 스토리지(스코프)에 대한 사용자의 권한.
 *
 * @param teamId 팀 스토리지면 팀 ID, 개인 스토리지면 null
 */
public record Access(Long teamId, boolean canRead, boolean canEdit, boolean canDelete, boolean canInvite,
                     boolean leader) {

    public static final Access NONE = new Access(null, false, false, false, false, false);

    /** 개인 스토리지의 소유자: 초대·팀장 개념이 없고 나머지는 모두 허용. */
    public static Access personalOwner() {
        return new Access(null, true, true, true, false, false);
    }

    public static Access of(TeamMember member) {
        return new Access(member.getTeam().getId(), true, member.mayEdit(), member.mayDelete(), member.mayInvite(),
                member.isTeamLeader());
    }

    public boolean isTeam() {
        return teamId != null;
    }
}
