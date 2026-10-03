package com.smartcollab.user;

import com.smartcollab.chat.ChatMessageRepository;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.file.DriveCleanupService;
import com.smartcollab.file.FileRepository;
import com.smartcollab.file.FileVersionRepository;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.notification.NotificationRepository;
import com.smartcollab.share.ShareLinkRepository;
import com.smartcollab.signature.SignatureRepository;
import com.smartcollab.team.InvitationRepository;
import com.smartcollab.team.TeamMemberRepository;
import com.smartcollab.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 회원 탈퇴.
 * <p>v1 은 비밀번호 재확인 없이 즉시 탈퇴했고, 다음 참조를 정리하지 않아 탈퇴가 FK 오류로 실패할 수 있었습니다:
 * 팀 파일의 버전 작성자(file_versions.editor_id), 나에게 걸린 공유 링크, 휴지통 파일이 남은 개인 폴더.</p>
 * <ul>
 *   <li>개인 스토리지: 폴더·파일(휴지통 포함)·버전·서명·공유 링크를 모두 영구 삭제</li>
 *   <li>팀 스토리지: 내가 만든 폴더·파일·버전·채팅은 팀 자료이므로 남기고, 작성자를 "탈퇴한 사용자"로 바꿈</li>
 *   <li>초대·알림·멤버십·내가 만든 공유 링크·서명 삭제</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository users;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final InvitationRepository invitations;
    private final NotificationRepository notifications;
    private final FolderRepository folders;
    private final FileRepository files;
    private final FileVersionRepository versions;
    private final ShareLinkRepository shareLinks;
    private final SignatureRepository signatures;
    private final ChatMessageRepository chatMessages;
    private final DriveCleanupService cleanup;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final DemoAccounts demoAccounts;

    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        demoAccounts.forbidIfDemo(user, "체험 계정은 탈퇴할 수 없습니다.");
        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호가 일치하지 않습니다.");
        }
        List<String> ledTeams = teams.findNamesOwnedBy(userId);
        if (!ledTeams.isEmpty()) {
            throw ApiException.conflict("팀장으로 있는 팀(" + String.join(", ", ledTeams)
                    + ")의 팀장을 위임하거나 팀을 삭제한 뒤 탈퇴할 수 있습니다.");
        }
        purgeAccount(userId);
    }

    /**
     * 계정과 개인 데이터를 지우고 팀 자료는 시스템 계정으로 넘깁니다. <b>비밀번호·체험 계정 여부는 확인하지 않으므로</b>
     * 호출하는 쪽이 확인해야 합니다 (회원 탈퇴 API, 데모 데이터 초기화). 팀장인 팀이 남아 있으면 지울 수 없습니다.
     */
    @Transactional
    public void purgeAccount(Long userId) {
        if (!teams.findIdsOwnedBy(userId).isEmpty()) {
            throw new IllegalStateException("팀장인 팀을 먼저 삭제하거나 위임해야 합니다: user " + userId);
        }
        User system = users.findFirstByRole(Role.SYSTEM)
                .orElseThrow(() -> new IllegalStateException("시스템 계정이 없습니다."));
        List<Long> teamIds = members.findMembershipsOf(userId).stream().map(m -> m.getTeam().getId()).toList();

        for (Folder root : folders.findPersonalRoots(userId)) {
            cleanup.deleteFolderTree(root.getId());
        }
        shareLinks.deleteByOwner(userId);
        signatures.deleteBySigner(userId);
        folders.transferTeamFolders(userId, system);
        files.transferTeamFiles(userId, system);
        versions.transferEditor(userId, system);
        chatMessages.transferSender(userId, system);
        invitations.deleteByUser(userId);
        notifications.deleteByUser(userId);
        members.deleteByUser(userId);
        users.deleteById(userId);

        teamIds.forEach(teamId -> {
            events.publishEvent(new ChangeEvents.MembershipRevoked(teamId, userId));
            events.publishEvent(new ChangeEvents.TeamChanged(teamId, ChangeEvents.TeamChangeType.MEMBERS_CHANGED));
        });
    }
}
