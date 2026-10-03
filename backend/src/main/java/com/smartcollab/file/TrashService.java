package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.FolderStructureLock;
import com.smartcollab.folder.FolderTrash;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.tx.TransactionRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final AccessPolicy accessPolicy;
    private final DriveCleanupService cleanup;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionRunner tx;
    private final FolderStructureLock structureLock;
    private final FolderTrash folderTrash;

    /** 자동 비우기에서 한 트랜잭션으로 지우는 파일 수 [PERF-05] */
    static final int PURGE_BATCH = 500;

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

    @Transactional
    public void restore(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        file.restoreFromTrash();
        Long teamId = file.getFolder().teamId();
        if (teamId != null) {
            events.publishEvent(new ChangeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    /**
     * 폴더를 하위 폴더·파일과 함께 복원합니다 [UX-06]. 원래 상위 폴더가 휴지통에 있으면(따로 지운 뒤 상위 폴더도 지운 경우)
     * 그 스토리지의 최상위 폴더로 복원합니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriveDtos.RestoreResponse restoreFolder(Long folderId, Long userId) {
        structureLock.lockScopesOf(List.of(folderId));   // 복원과 영구 삭제·자동 비우기가 엇갈리지 않게 [S-06]
        Folder folder = folders.findById(folderId).orElseThrow(() -> ApiException.notFound("휴지통의 폴더"));
        accessPolicy.requireTrashedFolderManage(folder, userId);
        Long teamId = folder.teamId();
        FolderTrash.Restored restored = folderTrash.restore(folder);
        if (teamId != null) {
            events.publishEvent(new ChangeEvents.FolderChanged(teamId, restored.destinationId()));
        }
        return new DriveDtos.RestoreResponse(restored.destinationId(), restored.relocated());
    }

    @Transactional
    public void deletePermanently(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        cleanup.purgeFiles(List.of(file.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteFolderPermanently(Long folderId, Long userId) {
        structureLock.lockScopesOf(List.of(folderId));
        Folder folder = folders.findById(folderId).orElseThrow(() -> ApiException.notFound("휴지통의 폴더"));
        accessPolicy.requireTrashedFolderManage(folder, userId);
        cleanup.deleteFolderTree(folderId);
    }

    /** @return 지운 항목 수 (파일 + 폴더) */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int empty(Long teamId, Long userId) {
        structureLock.lockScope(teamId, userId);   // 그 사이 복원된 폴더를 지우지 않게 [S-06]
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
                // 대상을 고른 뒤 복원됐을 수 있어, 잠근 뒤 아직 휴지통에 있는지 다시 확인합니다 [S-06]
                boolean purgedThis = tx.writeReadCommitted(() -> {
                    structureLock.lockScopesOf(List.of(folderId));
                    Folder folder = folders.findById(folderId).orElse(null);
                    if (folder == null || !folder.isTrashRoot() || folder.getDeletedAt().isAfter(cutoff)) {
                        return false;
                    }
                    cleanup.deleteFolderTree(folderId);
                    return true;
                });
                if (purgedThis) purgedFolders++;
            } catch (RuntimeException e) {
                log.error("Trash purge of folder {} failed; will retry at the next run", folderId, e);
            }
        }
        log.info("Purged {} trashed files and {} folders older than {} in {} batches", purged, purgedFolders, cutoff, batches);
    }

    private void requireTeamTrashAccess(Long teamId, Long userId) {
        accessPolicy.requireTeamTrash(teamId, userId);
    }

    private FileEntity getTrashed(Long fileId) {
        FileEntity file = files.findWithFolder(fileId).orElseThrow(() -> ApiException.notFound("파일"));
        if (!file.isDeleted()) {
            throw ApiException.notFound("휴지통의 파일");
        }
        return file;
    }
}
