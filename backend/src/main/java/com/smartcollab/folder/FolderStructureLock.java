package com.smartcollab.folder;

import com.smartcollab.team.TeamRepository;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 폴더 구조 변경(생성·이동·휴지통·복원·영구 삭제·복사 반영)을 저장 공간 단위로 줄 세웁니다 [S-06].
 * <p>저장 공간의 주인 행(개인=사용자, 팀=팀)을 잠근 뒤 최신 커밋 데이터로 다시 판단해야 하므로, 호출하는 트랜잭션은
 * READ COMMITTED 여야 하고 잠그기 전에는 폴더 엔티티를 읽지 않아야 합니다(먼저 읽은 엔티티는 잠근 뒤에도 옛 값으로 남음).
 * 같은 행을 저장 한도 확인({@code StorageQuota.lockAndCheckRoom})도 잠그므로 업로드와도 순서가 맞춰집니다.</p>
 * <p>이 잠금이 없을 때는 A→B·B→A 동시 이동으로 순환이 생겨 그 폴더와 팀·계정을 지울 수 없었고, 휴지통에 들어가는 폴더 아래에
 * 동시에 만든 폴더가 "살아 있는" 채로 남아 30일 뒤 함께 영구 삭제됐습니다.</p>
 */
@Component
@RequiredArgsConstructor
public class FolderStructureLock {

    private final FolderRepository folders;
    private final UserRepository users;
    private final TeamRepository teams;

    /** 폴더들이 속한 저장 공간을 잠급니다. 교착을 피하려고 사용자 행 → 팀 행, 각각 ID 순으로 잠급니다. 없는 폴더는 건너뜁니다. */
    public void lockScopesOf(Collection<Long> folderIds) {
        requireReadCommitted();
        SortedSet<Long> userIds = new TreeSet<>();
        SortedSet<Long> teamIds = new TreeSet<>();
        for (Long id : folderIds) {
            folders.findScope(id).ifPresent(scope -> {
                if (scope.teamId() != null) {
                    teamIds.add(scope.teamId());
                } else {
                    userIds.add(scope.ownerId());
                }
            });
        }
        userIds.forEach(users::lockById);
        teamIds.forEach(teams::lockById);
    }

    /** 개인(teamId == null) 또는 팀 저장 공간을 잠급니다. */
    public void lockScope(Long teamId, Long ownerId) {
        requireReadCommitted();
        if (teamId != null) {
            teams.lockById(teamId);
        } else {
            users.lockById(ownerId);
        }
    }

    private static void requireReadCommitted() {
        Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || level == null
                || level != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("폴더 구조 잠금은 READ COMMITTED 트랜잭션 안에서 써야 합니다(잠근 뒤 최신 데이터를 읽기 위함).");
        }
    }
}
