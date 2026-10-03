package com.smartcollab.file;

import com.smartcollab.event.DeletionEvents;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 계정·팀 삭제 때 스토리지(폴더·파일·버전)를 정리합니다 [A-02]. 지우는 쪽(AccountService·TeamService)의 트랜잭션 안에서 동기로 실행됩니다.
 */
@Component
@RequiredArgsConstructor
class DriveDeletionListener {

    private final FolderRepository folders;
    private final FileRepository files;
    private final FileVersionRepository versions;
    private final UserRepository users;
    private final DriveCleanupService cleanup;

    /** 개인 스토리지를 휴지통까지 모두 영구 삭제합니다. */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_PERSONAL_DRIVE)
    void deletePersonalDrive(DeletionEvents.AccountDeleting event) {
        for (Folder root : folders.findPersonalRoots(event.userId())) {
            cleanup.deleteFolderTree(root.getId());
        }
    }

    /** 팀 스토리지에서 내가 만든 폴더·파일·버전은 팀 자료이므로 남기고, 작성자를 시스템 계정("탈퇴한 사용자")으로 바꿉니다. */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_TEAM_DRIVE)
    void transferTeamDrive(DeletionEvents.AccountDeleting event) {
        User system = users.getReferenceById(event.systemUserId());
        folders.transferTeamFolders(event.userId(), system);
        files.transferTeamFiles(event.userId(), system);
        versions.transferEditor(event.userId(), system);
    }

    @EventListener
    @Order(DeletionEvents.Order.TEAM_DRIVE)
    void deleteTeamDrive(DeletionEvents.TeamDeleting event) {
        for (Folder root : folders.findTeamRoots(event.teamId())) {
            cleanup.deleteFolderTree(root.getId());
        }
    }
}
