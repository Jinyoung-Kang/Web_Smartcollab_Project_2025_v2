package com.smartcollab.access;

import com.smartcollab.file.FileEntity;
import com.smartcollab.folder.Folder;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.team.TeamMember;
import com.smartcollab.team.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 파일·폴더·팀 권한 판단을 한 곳에 모았습니다.
 * <p>v1 은 같은 규칙을 서비스 4곳에 따로 구현해 서로 달랐고, 다운로드·미리보기·버전 기록·채팅 기록 등
 * 여러 API 에 권한 검사 자체가 없었습니다(다른 사람의 파일 ID 만 알면 열람 가능).</p>
 * <ul>
 *   <li>읽을 수 없는 대상은 존재 여부를 숨기기 위해 404 로 응답합니다 (ID 추측 공격 방지).</li>
 *   <li>읽을 수는 있지만 권한이 부족하면 403 으로 응답합니다.</li>
 *   <li>휴지통에 있는 폴더와 그 안의 폴더·파일은 없는 것처럼 404 입니다 [UX-06]. 휴지통에서 복원·영구 삭제할 때만
 *       {@link #requireTrashedFolderManage} 로 따로 확인합니다. 모든 폴더·파일 작업이 이 클래스를 거치므로
 *       휴지통 안의 항목을 여는 경로(다운로드·편집·업로드·이동·공유 …)가 한 곳에서 막힙니다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccessPolicy {

    private final TeamMemberRepository members;

    public Access accessTo(Folder folder, Long userId) {
        if (folder.getTeam() == null) {
            return folder.getOwner().getId().equals(userId) ? Access.personalOwner() : Access.NONE;
        }
        return members.findByTeamIdAndUserId(folder.getTeam().getId(), userId)
                .map(Access::of)
                .orElse(Access.NONE);
    }

    public Access requireRead(Folder folder, Long userId) {
        Access access = accessTo(folder, userId);
        if (!access.canRead() || folder.isInTrash()) {
            throw ApiException.notFound("폴더");
        }
        return access;
    }

    public Access requireEdit(Folder folder, Long userId) {
        Access access = requireRead(folder, userId);
        if (!access.canEdit()) {
            throw ApiException.forbidden("이 폴더를 편집할 권한이 없습니다.");
        }
        return access;
    }

    public Access requireDelete(Folder folder, Long userId) {
        Access access = requireRead(folder, userId);
        if (!access.canDelete()) {
            throw ApiException.forbidden("이 폴더에서 삭제할 권한이 없습니다.");
        }
        return access;
    }

    /** 파일이 개별로 휴지통에 있는지는 호출하는 쪽이 판단합니다(휴지통 복원·영구 삭제도 이 검사를 씀). */
    public Access requireFileRead(FileEntity file, Long userId) {
        Access access = accessTo(file.getFolder(), userId);
        if (!access.canRead() || file.getFolder().isInTrash()) {
            throw ApiException.notFound("파일");
        }
        return access;
    }

    public Access requireFileEdit(FileEntity file, Long userId) {
        Access access = requireFileRead(file, userId);
        if (!access.canEdit()) {
            throw ApiException.forbidden("이 파일을 수정할 권한이 없습니다.");
        }
        return access;
    }

    /** 파일은 삭제 권한이 있거나, 자신이 올린 파일이면 지울 수 있습니다. */
    public Access requireFileDelete(FileEntity file, Long userId) {
        Access access = requireFileRead(file, userId);
        if (!access.canDelete() && !file.isOwnedBy(userId)) {
            throw ApiException.forbidden("이 파일을 삭제할 권한이 없습니다.");
        }
        return access;
    }

    /** 외부 공유 링크는 파일 소유자 또는 팀장만 만들 수 있습니다 (팀 자료의 외부 유출 통제). */
    public Access requireShare(FileEntity file, Long userId) {
        Access access = requireFileRead(file, userId);
        if (!mayShare(file, userId, access)) {
            throw ApiException.forbidden("파일을 올린 사람 또는 팀장만 공유 링크를 만들 수 있습니다.");
        }
        return access;
    }

    /**
     * 이 사용자가 지금 이 파일의 공유 링크를 만들 수 있는지. 공유 링크를 쓸 때마다 링크를 만든 사람으로 다시 확인해,
     * 팀에서 나간 사람이 만든 링크로 이후 팀이 고친 최신 버전이 계속 밖으로 나가지 않게 합니다 [S-09].
     */
    public boolean canShare(FileEntity file, Long userId) {
        return mayShare(file, userId, accessTo(file.getFolder(), userId));
    }

    private static boolean mayShare(FileEntity file, Long userId, Access access) {
        return access.canRead() && (file.isOwnedBy(userId) || access.leader());
    }

    /** 휴지통의 폴더를 복원·영구 삭제하려면 그 스토리지에서 삭제 권한이 있어야 합니다 [UX-06]. */
    public Access requireTrashedFolderManage(Folder folder, Long userId) {
        Access access = accessTo(folder, userId);
        if (!access.canRead() || !folder.isTrashRoot()) {
            throw ApiException.notFound("휴지통의 폴더");
        }
        if (!access.canDelete()) {
            throw ApiException.forbidden("삭제 권한이 있어야 휴지통의 폴더를 복원하거나 영구 삭제할 수 있습니다.");
        }
        return access;
    }

    /** 서명: 개인 파일은 소유자, 팀 파일은 팀장만 할 수 있습니다 [A-06]. 읽을 수 없으면 404. */
    public Access requireSign(FileEntity file, Long userId) {
        Access access = requireFileRead(file, userId);
        boolean allowed = access.isTeam() ? access.leader() : file.isOwnedBy(userId);
        if (!allowed) {
            throw ApiException.forbidden(access.isTeam() ? "팀 파일은 팀장만 서명할 수 있습니다." : "파일 소유자만 서명할 수 있습니다.");
        }
        return access;
    }

    /**
     * 팀 채팅에 공유할 수 있는 파일인지: 그 팀 스토리지에 있고 휴지통에 있지 않아야 합니다(아니면 존재를 숨기고 404) [A-06].
     * 보내는 사람이 팀 멤버인지는 {@link #requireMember} 로 따로 확인합니다.
     */
    public void requireTeamChatFile(FileEntity file, Long teamId) {
        if (file.isInTrash() || !Objects.equals(file.getFolder().teamId(), teamId)) {
            throw ApiException.notFound("이 팀의 파일");
        }
    }

    /** 팀 휴지통(보기·복원·영구 삭제·비우기)은 삭제 권한이 있는 멤버만 [A-06] */
    public TeamMember requireTeamTrash(Long teamId, Long userId) {
        TeamMember member = requireMember(teamId, userId);
        if (!member.mayDelete()) {
            throw ApiException.forbidden("팀 휴지통은 삭제 권한이 있는 멤버만 볼 수 있습니다.");
        }
        return member;
    }

    /** 팀원 초대는 초대 권한이 있는 멤버(팀장 포함)만 [A-06] */
    public TeamMember requireInvite(Long teamId, Long userId) {
        TeamMember member = requireMember(teamId, userId);
        if (!member.mayInvite()) {
            throw ApiException.forbidden("팀원 초대 권한이 없습니다.");
        }
        return member;
    }

    public TeamMember requireMember(Long teamId, Long userId) {
        return members.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> ApiException.notFound("팀"));
    }

    public TeamMember requireLeader(Long teamId, Long userId) {
        TeamMember member = requireMember(teamId, userId);
        if (!member.isTeamLeader()) {
            throw ApiException.forbidden("팀장만 할 수 있는 작업입니다.");
        }
        return member;
    }

    public boolean isMember(Long teamId, Long userId) {
        return members.existsByTeamIdAndUserId(teamId, userId);
    }
}
