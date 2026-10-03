package com.smartcollab.folder;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 폴더의 휴지통 상태를 바꿉니다 [UX-06·A-04]. 이전에는 파일 모듈(TrashService)이 폴더 리포지토리로 직접 바꿨습니다.
 * 권한·잠금·변경 알림은 호출하는 쪽(폴더 삭제·여러 항목 삭제·휴지통)이 맡습니다.
 */
@Component
@RequiredArgsConstructor
public class FolderTrash {

    private static final int CHUNK = 500;

    private final FolderRepository folders;
    private final UserRepository users;

    /**
     * 폴더를 하위 폴더까지 휴지통에 넣습니다. 이미 따로 휴지통에 넣은 하위 폴더는 그대로 두어 휴지통 목록에 따로 남습니다.
     * <p>일괄 UPDATE 가 영속성 컨텍스트를 비우므로, 호출하는 쪽은 이 뒤에 이전에 읽은 엔티티를 쓰지 않아야 합니다.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveToTrash(Folder folder, Long userId) {
        Long rootId = folder.getId();
        List<Long> subtree = folders.findSubtree(rootId).stream().map(FolderRepository.SubtreeRow::getId).toList();
        for (int from = 0; from < subtree.size(); from += CHUNK) {
            folders.markInTrash(subtree.subList(from, Math.min(from + CHUNK, subtree.size())), rootId);
        }
        folders.stampTrashRoot(rootId, Instant.now(), users.getReferenceById(userId));
    }

    /**
     * 휴지통의 폴더를 하위 폴더·파일과 함께 복원합니다. 원래 상위 폴더가 휴지통에 있으면(따로 지운 뒤 상위 폴더도 지운 경우)
     * 그 스토리지의 최상위 폴더로 옮겨 복원합니다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Restored restore(Folder folder) {
        boolean relocated = folder.getParent().isInTrash();
        Folder destination = relocated ? scopeRoot(folder) : folder.getParent();
        if (relocated) {
            folder.moveUnder(destination);
        }
        Restored restored = new Restored(destination.getId(), relocated);
        folders.restoreFromTrash(folder.getId());
        return restored;
    }

    /** 복원된 위치(상위 폴더 ID)와, 원래 자리가 아니라 최상위로 옮겼는지 */
    public record Restored(Long destinationId, boolean relocated) {
    }

    private Folder scopeRoot(Folder folder) {
        return (folder.getTeam() == null
                ? folders.findPersonalRoot(folder.getOwner().getId())
                : folders.findTeamRoot(folder.teamId()))
                .orElseThrow(() -> ApiException.notFound("최상위 폴더"));
    }
}
