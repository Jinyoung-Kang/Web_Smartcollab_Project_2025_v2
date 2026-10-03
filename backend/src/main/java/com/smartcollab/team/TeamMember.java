package com.smartcollab.team;

import com.smartcollab.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자-팀 소속과 멤버별 권한.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "team_members")
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_member_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "is_team_leader", nullable = false)
    private boolean teamLeader;

    @Column(name = "can_edit", nullable = false)
    private boolean canEdit;

    @Column(name = "can_delete", nullable = false)
    private boolean canDelete;

    @Column(name = "can_invite", nullable = false)
    private boolean canInvite;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    private TeamMember(Team team, User user, boolean leader, boolean canEdit, boolean canDelete, boolean canInvite) {
        this.team = team;
        this.user = user;
        this.teamLeader = leader;
        this.canEdit = canEdit;
        this.canDelete = canDelete;
        this.canInvite = canInvite;
        this.joinedAt = Instant.now();
    }

    public static TeamMember leader(Team team, User user) {
        return new TeamMember(team, user, true, true, true, true);
    }

    /**
     * 초대를 수락한 새 멤버: 삭제·초대 권한 없이, 편집 권한은 초대한 사람이 편집할 수 있을 때만 줍니다.
     * 이전에는 항상 편집 권한이라, 편집 권한 없이 초대 권한만 받은 멤버가 편집 권한 계정을 들일 수 있었습니다 [S-08].
     */
    public static TeamMember invitedBy(TeamMember inviter, User user) {
        return new TeamMember(inviter.getTeam(), user, false, inviter.mayEdit(), false, false);
    }

    public void updatePermissions(boolean canEdit, boolean canDelete, boolean canInvite) {
        this.canEdit = canEdit;
        this.canDelete = canDelete;
        this.canInvite = canInvite;
    }

    void promoteToLeader() {
        this.teamLeader = true;
        this.canEdit = true;
        this.canDelete = true;
        this.canInvite = true;
    }

    /** 팀장을 넘긴 뒤에는 편집 권한만 유지합니다. */
    void demoteFromLeader() {
        this.teamLeader = false;
        this.canEdit = true;
        this.canDelete = false;
        this.canInvite = false;
    }

    public boolean mayEdit() {
        return teamLeader || canEdit;
    }

    public boolean mayDelete() {
        return teamLeader || canDelete;
    }

    public boolean mayInvite() {
        return teamLeader || canInvite;
    }
}
