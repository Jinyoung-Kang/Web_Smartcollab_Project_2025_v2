package com.smartcollab.file;

import com.smartcollab.folder.FolderRepository;
import com.smartcollab.share.ShareLinkRepository;
import com.smartcollab.signature.SignatureRepository;
import com.smartcollab.storage.BlobLifecycle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 파일·폴더 영구 삭제를 한 곳에서 처리합니다 (폴더 삭제·휴지통 비우기·팀 삭제·회원 탈퇴 공통).
 * <p>v1 의 문제:
 * <ul>
 *   <li>휴지통에 있는 파일을 빼고 지워 폴더 삭제가 FK 오류로 실패</li>
 *   <li>서명·활성 버전 참조를 정리하지 않아 서명된 파일의 영구 삭제가 FK 오류로 실패</li>
 *   <li>폴더를 재귀 호출로 하나씩 지워 폴더 수만큼 쿼리 발생</li>
 * </ul>
 * v2 는 대상 ID 를 재귀 CTE 한 번으로 모은 뒤, 참조 관계 순서대로 일괄(bulk) 삭제하고
 * 저장소 파일은 커밋 이후에 지웁니다.</p>
 */
@Service
@RequiredArgsConstructor
public class DriveCleanupService {

    private static final int CHUNK = 500;

    private final FileRepository files;
    private final FileVersionRepository versions;
    private final SignatureRepository signatures;
    private final ShareLinkRepository shareLinks;
    private final FolderRepository folders;
    private final BlobLifecycle blobs;

    /** 파일과 그 버전·서명·공유 링크를 영구 삭제합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeFiles(Collection<Long> fileIds) {
        if (fileIds.isEmpty()) return 0;
        List<String> blobKeys = new ArrayList<>();
        for (List<Long> chunk : chunks(fileIds)) {
            blobKeys.addAll(versions.findStoredPaths(chunk));
            shareLinks.deleteByFileIds(chunk);
            signatures.deleteByFileIds(chunk);
            files.detachActiveVersions(chunk);   // files ↔ file_versions 순환 참조를 먼저 끊음
            versions.deleteByFileIds(chunk);
            files.deleteAllByIds(chunk);
        }
        blobs.deleteAfterCommit(blobKeys);
        return fileIds.size();
    }

    /** 폴더와 모든 하위 폴더·파일(휴지통 포함)을 영구 삭제합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteFolderTree(Long folderId) {
        List<FolderRepository.SubtreeRow> subtree = folders.findSubtree(folderId);
        if (subtree.isEmpty()) return 0;
        List<Long> folderIds = subtree.stream().map(FolderRepository.SubtreeRow::getId).toList();

        List<Long> fileIds = new ArrayList<>();
        for (List<Long> chunk : chunks(folderIds)) {
            fileIds.addAll(files.findIdsInFolders(chunk));
        }
        purgeFiles(fileIds);

        // 자식 → 부모 순서(깊이 역순)로 지워 parent FK 를 위반하지 않게 합니다.
        Map<Integer, List<Long>> byDepth = new TreeMap<>(Comparator.reverseOrder());
        for (FolderRepository.SubtreeRow row : subtree) {
            byDepth.computeIfAbsent(row.getDepth(), d -> new ArrayList<>()).add(row.getId());
        }
        for (List<Long> level : byDepth.values()) {
            for (List<Long> chunk : chunks(level)) {
                folders.deleteAllByIds(chunk);
            }
        }
        return folderIds.size();
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> list = List.copyOf(ids);
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK) {
            out.add(list.subList(i, Math.min(i + CHUNK, list.size())));
        }
        return out;
    }
}
