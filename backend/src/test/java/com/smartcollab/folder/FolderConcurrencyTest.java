package com.smartcollab.folder;

import com.smartcollab.file.DriveDtos;
import com.smartcollab.file.ItemTransferService;
import com.smartcollab.file.TrashService;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S-06] 폴더 구조를 동시에 바꿀 때의 경합. 다른 트랜잭션이 저장 공간 행을 잠근 채 구조를 바꾼 상태를 래치로 만들고,
 * 그동안 실제 서비스 호출을 보내 순서를 결정적으로 재현합니다.
 * <ul>
 *   <li>수정 전: 서비스가 잠그지 않아 바로 진행하고, 커밋되지 않은 변경 이전의 스냅샷으로 판단해 잘못된 구조를 커밋</li>
 *   <li>수정 후: 서비스가 같은 행을 잠가 기다리고, 상대가 커밋한 뒤의 최신 데이터로 다시 판단</li>
 * </ul>
 */
class FolderConcurrencyTest extends IntegrationTest {

    @Autowired
    FolderRepository folders;
    @Autowired
    UserRepository users;
    @Autowired
    FolderService folderService;
    @Autowired
    ItemTransferService transfer;
    @Autowired
    TrashService trashService;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    TransactionTemplate tx;

    /** 다른 트랜잭션이 저장 공간 행을 잠근 채 change 를 실행해 붙든 동안 action 을 보냅니다 ({@link TransactionRace}). */
    private Throwable raceAgainst(long userId, Runnable change, Runnable action) throws Exception {
        try (TransactionRace race = new TransactionRace(tx)) {
            return race.run(() -> {
                users.lockById(userId);
                change.run();
                folders.flush();
            }, action);
        }
    }

    private Long parentOf(long folderId) {
        return jdbc.queryForObject("select parent_folder_id from folders where folder_id = ?", Long.class, folderId);
    }

    @Test
    @DisplayName("[S-06] 폴더를 휴지통에 넣는 동안 하위 폴더 이름을 바꿔도 휴지통 표시가 지워지지 않는다")
    void renameDoesNotClearTrashMark() {
        Api.Session s = api().signUp("crename");
        long p = s.createFolder(s.rootFolderId, "상위");
        long c = s.createFolder(p, "하위");
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(st -> {
            Folder child = folders.findById(c).orElseThrow();                       // 이름 변경 요청이 하위 폴더를 읽음
            requiresNew.executeWithoutResult(inner ->                                // 그 사이 다른 요청이 상위 폴더를 휴지통에 넣고 커밋
                    trashService.moveFolderToTrash(folders.findById(p).orElseThrow(), s.userId));
            child.rename("새 이름");                                                // 이름 변경 커밋
        });

        assertThat(jdbc.queryForObject("select trash_root_id from folders where folder_id = ?", Long.class, c)).isEqualTo(p);
        assertThat(jdbc.queryForObject("select name from folders where folder_id = ?", String.class, c)).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("[S-06] A→B, B→A 를 동시에 옮겨도 순환이 생기지 않는다")
    void opposingMovesCannotCreateCycle() throws Exception {
        Api.Session s = api().signUp("ccycle");
        long a = s.createFolder(s.rootFolderId, "A");
        long b = s.createFolder(s.rootFolderId, "B");

        Throwable result = raceAgainst(s.userId,
                () -> folders.findById(b).orElseThrow().moveUnder(folders.findById(a).orElseThrow()),   // B 를 A 아래로
                () -> transfer.move(new DriveDtos.TransferRequest(List.of(new DriveDtos.ItemRef("folder", a)), b), s.userId));

        assertThat(result).isInstanceOf(ApiException.class).hasMessageContaining("하위 폴더 안으로");
        assertThat(parentOf(b)).isEqualTo(a);
        assertThat(parentOf(a)).isEqualTo(s.rootFolderId);
    }

    @Test
    @DisplayName("[S-06] 휴지통에 들어가는 폴더 아래에는 동시에 폴더를 만들 수 없다 (살아 있는 폴더가 남아 30일 뒤 함께 지워지던 문제)")
    void cannotCreateUnderFolderBeingTrashed() throws Exception {
        Api.Session s = api().signUp("ccreate");
        long p = s.createFolder(s.rootFolderId, "상위");
        long c = s.createFolder(p, "하위");

        Throwable result = raceAgainst(s.userId,
                () -> trashService.moveFolderToTrash(folders.findById(p).orElseThrow(), s.userId),
                () -> folderService.create(new DriveDtos.CreateFolderRequest(c, "새 폴더"), s.userId));

        assertThat(result).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from folders where parent_folder_id = ?", Integer.class, c)).isZero();
    }
}
