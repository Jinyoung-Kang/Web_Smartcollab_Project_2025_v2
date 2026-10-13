package com.smartcollab.admin;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.file.StorageQuota;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.security.SecurityEventLog;
import com.smartcollab.team.TeamService;
import com.smartcollab.team.UserTeam;
import com.smartcollab.user.AccountDirectory;
import com.smartcollab.user.AccountService;
import com.smartcollab.user.DemoAccounts;
import com.smartcollab.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 관리 전용 계정의 계정 목록·삭제 (ADR-0015).
 * <p>목록은 아이디·이름·소속 팀 이름·개인 드라이브 사용량·가입일만 읽습니다. 계정의 파일·폴더·채팅은 이 모듈이 읽는 경로 자체가 없습니다.
 * 다른 모듈의 리포지토리를 쓰지 않고 각 모듈의 서비스를 거칩니다 (ADR-0010).</p>
 */
@Service
@RequiredArgsConstructor
public class AdminService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final AccessPolicy accessPolicy;
    private final AccountDirectory accounts;
    private final AccountService accountService;
    private final TeamService teamService;
    private final StorageQuota storageQuota;
    private final DemoAccounts demoAccounts;

    /** 일반 계정 목록 (가입일 최신순). 시스템 계정("탈퇴한 사용자")과 관리자 계정은 빠집니다. */
    @Transactional(readOnly = true)
    public AdminDtos.AccountPage accounts(Integer page, Integer size, Long adminId) {
        accessPolicy.requireAdmin(adminId);
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0 || s < 1 || s > MAX_SIZE) {
            throw ApiException.badRequest("page 는 0 이상, size 는 1~" + MAX_SIZE + " 이어야 합니다.");
        }
        Page<User> users = accounts.members(PageRequest.of(p, s));
        List<Long> ids = users.getContent().stream().map(User::getId).toList();
        Map<Long, Long> used = accounts.storedBytes(ids);
        Map<Long, List<UserTeam>> teams = teamService.teamsOf(ids);
        List<AdminDtos.AccountRow> rows = users.getContent().stream()
                .map(u -> new AdminDtos.AccountRow(u.getId(), u.getUsername(), u.getName(),
                        teams.getOrDefault(u.getId(), List.of()).stream().map(t -> new AdminDtos.TeamRow(t.teamName(), t.leader())).toList(),
                        used.getOrDefault(u.getId(), 0L),
                        storageQuota.limitBytes(StorageQuota.Scope.personal(u.getId())),   // 같은 트랜잭션에서 읽은 사용자라 다시 조회하지 않음
                        u.getCreatedAt(), demoAccounts.isDemo(u)))
                .toList();
        return new AdminDtos.AccountPage(rows, p, s, users.getTotalElements(), users.getTotalPages());
    }

    /**
     * 계정 삭제(탈퇴 처리). 팀장인 팀은 먼저 다른 멤버에게 넘기거나(없으면 삭제) 정리한 뒤, 탈퇴와 같은 경로로 개인 드라이브를 영구 삭제하고
     * 팀에서 빼며, 커밋 뒤 그 계정의 로그인 토큰·실시간 연결을 끊습니다. 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않습니다.
     * <p>시스템·관리자 계정과 없는 ID 는 404, 체험 계정은 403 입니다(매일 초기화되며, 지우면 공개 데모가 깨짐).</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminDtos.DeletionResult deleteAccount(Long userId, Long adminId) {
        accessPolicy.requireAdmin(adminId);
        User target = accounts.findMember(userId).orElseThrow(() -> ApiException.notFound("계정"));
        demoAccounts.forbidIfDemo(target, "체험 계정은 삭제할 수 없습니다.");
        TeamService.LedTeamsHandOver handOver = teamService.handOverLedTeams(userId);
        accountService.purgeAccount(userId);
        SecurityEventLog.accountDeletedByAdmin(adminId, userId);
        return new AdminDtos.DeletionResult(handOver.transferred(), handOver.deleted());
    }
}
