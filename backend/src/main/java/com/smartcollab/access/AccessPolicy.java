package com.smartcollab.access;

import com.smartcollab.file.FileEntity;
import com.smartcollab.folder.Folder;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.team.TeamMember;
import com.smartcollab.team.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 파일·폴더·팀 권한 판단을 한 곳에 모았습니다.
 * <p>v1 은 같은 규칙을 서비스 4곳에 따로 구현해 서로 달랐고, 다운로드·미리보기·버전 기록·채팅 기록 등
 * 여러 API 에 권한 검사 자체가 없었습니다(다른 사람의 파일 ID 만 알면 열람 가능).</p>
 * <ul>
 *   <li>읽을 수 없는 대상은 존재 여부를 숨기기 위해 404 로 응답합니다 (ID 추측 공격 방지).</li>
 *   <li>읽을 수는 있지만 권한이 부족하면 403 으로 응답합니다.</li>
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
        if (!access.canRead()) {
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

    public Access requireFileRead(FileEntity file, Long userId) {
        Access access = accessTo(file.getFolder(), userId);
        if (!access.canRead()) {
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
        if (!file.isOwnedBy(userId) && !access.leader()) {
            throw ApiException.forbidden("파일을 올린 사람 또는 팀장만 공유 링크를 만들 수 있습니다.");
        }
        return access;
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
