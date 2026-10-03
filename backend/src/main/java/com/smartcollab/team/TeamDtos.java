package com.smartcollab.team;

import com.smartcollab.access.PermissionsResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TeamDtos {

    private TeamDtos() {
    }

    public record CreateTeamRequest(
            @NotBlank(message = "팀 이름을 입력하세요.") @Size(max = 100, message = "팀 이름은 100자 이하입니다.") String name) {
    }

    public record InviteRequest(@NotBlank(message = "초대할 사용자의 아이디를 입력하세요.") String username) {
    }

    public record PermissionRequest(boolean canEdit, boolean canDelete, boolean canInvite) {
    }

    public record TeamSummary(Long id, String name, String ownerName, long memberCount, Long rootFolderId,
                              PermissionsResponse myPermissions) {
    }

    public record MemberResponse(Long memberId, Long userId, String username, String name, boolean leader,
                                 boolean canEdit, boolean canDelete, boolean canInvite, Instant joinedAt) {
        public static MemberResponse of(TeamMember m) {
            return new MemberResponse(m.getId(), m.getUser().getId(), m.getUser().getUsername(), m.getUser().getName(),
                    m.isTeamLeader(), m.mayEdit(), m.mayDelete(), m.mayInvite(), m.getJoinedAt());
        }
    }

    public record TeamDetail(Long id, String name, String ownerUsername, Long rootFolderId,
                             PermissionsResponse myPermissions, List<MemberResponse> members) {
    }

}
