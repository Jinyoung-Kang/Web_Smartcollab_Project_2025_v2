package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.FolderStructureLock;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 여러 항목 삭제 [PERF-03]. 이전에는 선택한 항목마다 요청·트랜잭션·실시간 알림이 하나씩 생겼습니다.
 * <ul>
 *   <li>한 트랜잭션이라 하나라도 지울 수 없으면(권한·존재) 아무것도 지우지 않습니다.</li>
 *   <li>팀 폴더의 변경 알림은 지운 항목 수와 상관없이 폴더당 한 번만 보냅니다.</li>
 *   <li>파일은 휴지통으로, 폴더는 안의 폴더·파일과 함께 휴지통으로 옮깁니다(단건 API 와 같은 규칙) [UX-06].
 *       파일을 먼저 처리합니다 — 폴더를 휴지통에 넣는 일괄 UPDATE 가 영속성 컨텍스트를 비우기 때문입니다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ItemDeletionService {

    private final FileRepository files;
    private final FolderRepository folders;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final TrashService trash;
    private final ApplicationEventPublisher events;
    private final FolderStructureLock structureLock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriveDtos.DeleteResponse delete(DriveDtos.DeleteRequest req, Long userId) {
        List<DriveDtos.ItemRef> refs = req.items().stream().distinct().toList();
        // 폴더를 휴지통에 넣는 동안 그 아래에 다른 요청이 폴더를 만들거나 옮기지 못하게 저장 공간을 먼저 잠급니다 [S-06]
        structureLock.lockScopesOf(refs.stream().filter(r -> r.type().equals("folder")).map(DriveDtos.ItemRef::id).toList());
        Set<RealtimeEvents.FolderChanged> changes = new LinkedHashSet<>();

        int trashed = 0;
        for (DriveDtos.ItemRef ref : refs) {
            if (!ref.type().equals("file")) continue;
            FileEntity file = files.findWithFolder(ref.id()).filter(Predicate.not(FileEntity::isDeleted))
                    .orElseThrow(() -> ApiException.notFound("파일"));
            accessPolicy.requireFileDelete(file, userId);
            file.moveToTrash(users.getReferenceById(userId));
            changed(changes, file.getFolder());
            trashed++;
        }

        int trashedFolders = 0;
        for (DriveDtos.ItemRef ref : refs) {
            if (!ref.type().equals("folder")) continue;
            Folder folder = folders.findById(ref.id()).orElseThrow(() -> ApiException.notFound("폴더"));
            accessPolicy.requireDelete(folder, userId);
            if (folder.isRoot()) {
                throw ApiException.badRequest("최상위 폴더는 삭제할 수 없습니다.");
            }
            changed(changes, folder.getParent());
            trash.moveFolderToTrash(folder, userId);
            trashedFolders++;
        }

        changes.forEach(events::publishEvent);
        return new DriveDtos.DeleteResponse(trashed, trashedFolders);
    }

    private static void changed(Set<RealtimeEvents.FolderChanged> changes, Folder folder) {
        if (folder.teamId() != null) {
            changes.add(new RealtimeEvents.FolderChanged(folder.teamId(), folder.getId()));
        }
    }
}
