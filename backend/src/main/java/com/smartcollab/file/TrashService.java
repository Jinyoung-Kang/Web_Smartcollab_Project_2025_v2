package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.team.TeamMember;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 휴지통. v1 은 삭제한 파일을 휴지통으로 옮기기만 하고 목록·복원 화면이 없어, 지운 파일이 저장소에 영원히 남았습니다.
 * v2 는 개인/팀 휴지통 화면과 복원·영구 삭제를 제공하고, 보관 기간(기본 30일)이 지나면 매일 자동으로 비웁니다.
 * <p>폴더도 휴지통을 거칩니다 [UX-06]. 폴더를 지우면 하위 폴더까지 휴지통 표시만 하고 안의 파일은 그대로 두어, 폴더가
 * 휴지통에 있는 동안 함께 숨겨지고 복원하면 함께 돌아옵니다. 휴지통 목록에는 맨 위 폴더만 보입니다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    private final FileRepository files;
    private final FolderRepository folders;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final DriveCleanupService cleanup;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionRunner tx;

    /** 자동 비우기에서 한 트랜잭션으로 지우는 파일 수 [PERF-05] */
    static final int PURGE_BATCH = 500;
    private static final int CHUNK = 500;

    @Transactional(readOnly = true)
    public List<DriveDtos.TrashItem> list(Long teamId, Long userId) {
        List<FileEntity> trashedFiles;
        List<Folder> trashedFolders;
        if (teamId == null) {
            trashedFiles = files.findPersonalTrash(userId);
            trashedFolders = folders.findPersonalTrashRoots(userId);
        } else {
            requireTeamTrashAccess(teamId, userId);
            trashedFiles = files.findTeamTrash(teamId);
            trashedFolders = folders.findTeamTrashRoots(teamId);
        }
        Map<Long, TrashedTreeSize> sizes = trashedFolders.isEmpty() ? Map.of()
                : files.sizeOfTrashedTrees(trashedFolders.stream().map(Folder::getId).toList()).stream()
                .collect(Collectors.toMap(TrashedTreeSize::rootId, Function.identity()));

        List<DriveDtos.TrashItem> items = new ArrayList<>();
        trashedFolders.forEach(f -> items.add(DriveDtos.TrashItem.of(f, sizes.get(f.getId()))));
        trashedFiles.forEach(f -> items.add(DriveDtos.TrashItem.of(f)));
        items.sort(Comparator.comparing(DriveDtos.TrashItem::deletedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items;
    }

    /**
     * 폴더를 하위 폴더까지 휴지통에 넣습니다 [UX-06]. 권한·최상위 폴더 확인과 변경 알림은 호출하는 쪽이 합니다.
     * 이미 따로 휴지통에 넣은 하위 폴더는 그대로 두어 휴지통 목록에 따로 남습니다.
     * <p>일괄 UPDATE 가 영속성 컨텍스트를 비우므로, 호출하는 쪽은 이 뒤에 이전에 읽은 엔티티를 쓰지 않아야 합니다.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveFolderToTrash(Folder folder, Long userId) {
        Long rootId = folder.getId();
        List<Long> subtree = folders.findSubtree(rootId).stream().map(FolderRepository.SubtreeRow::getId).toList();
        for (int from = 0; from < subtree.size(); from += CHUNK) {
            folders.markInTrash(subtree.subList(from, Math.min(from + CHUNK, subtree.size())), rootId);
        }
        folders.stampTrashRoot(rootId, Instant.now(), users.getReferenceById(userId));
    }

    @Transactional
    public void restore(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        file.restoreFromTrash();
        Long teamId = file.getFolder().teamId();
        if (teamId != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    /**
     * 폴더를 하위 폴더·파일과 함께 복원합니다 [UX-06]. 원래 상위 폴더가 휴지통에 있으면(따로 지운 뒤 상위 폴더도 지운 경우)
     * 그 스토리지의 최상위 폴더로 복원합니다.
     */
    @Transactional
    public DriveDtos.RestoreResponse restoreFolder(Long folderId, Long userId) {
        Folder folder = folders.findById(folderId).orElseThrow(() -> ApiException.notFound("휴지통의 폴더"));
        accessPolicy.requireTrashedFolderManage(folder, userId);
        boolean relocated = folder.getParent().isInTrash();
        Folder destination = relocated ? scopeRoot(folder) : folder.getParent();
        if (relocated) {
            folder.moveUnder(destination);
        }
        Long destinationId = destination.getId();
        Long teamId = folder.teamId();
        folders.restoreFromTrash(folderId);
        if (teamId != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(teamId, destinationId));
        }
        return new DriveDtos.RestoreResponse(destinationId, relocated);
    }

    @Transactional
    public void deletePermanently(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        cleanup.purgeFiles(List.of(file.getId()));
    }

    @Transactional
    public void deleteFolderPermanently(Long folderId, Long userId) {
        Folder folder = folders.findById(folderId).orElseThrow(() -> ApiException.notFound("휴지통의 폴더"));
        accessPolicy.requireTrashedFolderManage(folder, userId);
        cleanup.deleteFolderTree(folderId);
    }

    /** @return 지운 항목 수 (파일 + 폴더) */
    @Transactional
    public int empty(Long teamId, Long userId) {
        List<Long> fileIds;
        List<Long> folderIds;
        if (teamId == null) {
            fileIds = files.findPersonalTrash(userId).stream().map(FileEntity::getId).toList();
            folderIds = folders.findPersonalTrashRoots(userId).stream().map(Folder::getId).toList();
        } else {
            requireTeamTrashAccess(teamId, userId);
            fileIds = files.findTeamTrash(teamId).stream().map(FileEntity::getId).toList();
            folderIds = folders.findTeamTrashRoots(teamId).stream().map(Folder::getId).toList();
        }
        cleanup.purgeFiles(fileIds);
        folderIds.forEach(cleanup::deleteFolderTree);   // 휴지통 폴더 안에 따로 휴지통에 넣은 폴더는 이미 지워졌으면 건너뜀
        return fileIds.size() + folderIds.size();
    }

    /**
     * 보관 기간이 지난 파일·폴더를 매일 영구 삭제합니다 (기본: Asia/Seoul 새벽 4시).
     * 시간대를 지정하지 않으면 서버 시간대를 따라, UTC 컨테이너에서는 한국 시각 오후 1시에 실행됐습니다 [BUG-05].
     * <p>파일은 {@value #PURGE_BATCH}개씩, 폴더는 트리마다 따로 커밋합니다 [PERF-05]. 한 트랜잭션으로 모두 지우면 대상이 많을 때
     * 잠금을 오래 쥐고, 하나만 실패해도 전부 되돌아갑니다. 실패한 배치는 다음 날 다시 대상이 됩니다.</p>
     */
    @Scheduled(cron = "${app.files.trash-purge-cron}", zone = "${app.files.trash-purge-zone}")
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.files().trashRetentionDays()));
        List<Long> ids = files.findTrashedBefore(cutoff);
        List<Long> folderIds = folders.findTrashRootsBefore(cutoff);
        if (ids.isEmpty() && folderIds.isEmpty()) return;
        int purged = 0;
        int batches = 0;
        for (int from = 0; from < ids.size(); from += PURGE_BATCH) {
            List<Long> batch = ids.subList(from, Math.min(from + PURGE_BATCH, ids.size()));
            try {
                purged += tx.write(() -> cleanup.purgeFiles(batch));
                batches++;
            } catch (RuntimeException e) {
                log.error("Trash purge batch failed ({} files); will retry at the next run", batch.size(), e);
            }
        }
        int purgedFolders = 0;
        for (Long folderId : folderIds) {
            try {
                tx.write(() -> cleanup.deleteFolderTree(folderId));
                purgedFolders++;
            } catch (RuntimeException e) {
                log.error("Trash purge of folder {} failed; will retry at the next run", folderId, e);
            }
        }
        log.info("Purged {} trashed files and {} folders older than {} in {} batches", purged, purgedFolders, cutoff, batches);
    }

    private void requireTeamTrashAccess(Long teamId, Long userId) {
        TeamMember member = accessPolicy.requireMember(teamId, userId);
        if (!member.mayDelete()) {
            throw ApiException.forbidden("팀 휴지통은 삭제 권한이 있는 멤버만 볼 수 있습니다.");
        }
    }

    private FileEntity getTrashed(Long fileId) {
        FileEntity file = files.findWithFolder(fileId).orElseThrow(() -> ApiException.notFound("파일"));
        if (!file.isDeleted()) {
            throw ApiException.notFound("휴지통의 파일");
        }
        return file;
    }

    private Folder scopeRoot(Folder folder) {
        return (folder.getTeam() == null
                ? folders.findPersonalRoot(folder.getOwner().getId())
                : folders.findTeamRoot(folder.teamId()))
                .orElseThrow(() -> ApiException.notFound("최상위 폴더"));
    }
}
