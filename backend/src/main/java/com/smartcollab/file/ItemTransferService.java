package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.storage.BlobLifecycle;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 파일·폴더 이동과 복사.
 * <p>v1 의 문제와 v2 의 처리:
 * <ul>
 *   <li>이동할 원본에 대한 권한을 확인하지 않아 남의 파일을 자기 폴더로 옮길 수 있었음 → 원본·대상 모두 편집 권한 확인</li>
 *   <li>폴더를 자기 하위 폴더로 옮기면 순환 구조가 생겨 트리 조회가 무한 재귀 → 재귀 CTE 로 순환 검사</li>
 *   <li>팀 폴더를 개인 드라이브로 옮기면 폴더는 개인, 안의 파일은 팀 소속인 모순 상태 → 스토리지 간 이동 금지(복사 사용)</li>
 *   <li>복사본에 버전 레코드를 만들지 않아 복사한 파일을 내려받을 수 없었음 → 현재 버전을 복사하고 첫 버전 생성</li>
 *   <li>폴더 복사 미지원 → 하위 구조까지 너비 우선(BFS)으로 복사</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class ItemTransferService {

    static final int MAX_COPY_FILES = 500;

    private final FileRepository files;
    private final FileVersionRepository versions;
    private final FolderRepository folders;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final BlobLifecycle blobLifecycle;
    private final ApplicationEventPublisher events;

    @Transactional
    public void move(DriveDtos.TransferRequest req, Long userId) {
        Folder target = getFolder(req.targetFolderId());
        accessPolicy.requireEdit(target, userId);
        Set<Folder> touched = new LinkedHashSet<>();
        touched.add(target);

        for (DriveDtos.ItemRef ref : req.items()) {
            if (ref.type().equals("folder")) {
                Folder folder = getFolder(ref.id());
                if (folder.isRoot()) {
                    throw ApiException.badRequest("최상위 폴더는 이동할 수 없습니다.");
                }
                accessPolicy.requireEdit(folder.getParent(), userId);
                requireSameScope(folder, target);
                boolean cycle = folders.findSubtree(folder.getId()).stream().anyMatch(r -> r.getId().equals(target.getId()));
                if (cycle) {
                    throw ApiException.badRequest("폴더를 자기 자신이나 하위 폴더 안으로 옮길 수 없습니다.");
                }
                touched.add(folder.getParent());
                folder.moveUnder(target);
            } else {
                FileEntity file = files.findWithFolder(ref.id()).filter(Predicate.not(FileEntity::isDeleted))
                        .orElseThrow(() -> ApiException.notFound("파일"));
                accessPolicy.requireFileEdit(file, userId);
                requireSameScope(file.getFolder(), target);
                touched.add(file.getFolder());
                file.moveTo(target);
            }
        }
        publishChanged(touched);
    }

    @Transactional
    public int copy(DriveDtos.TransferRequest req, Long userId) {
        Folder target = getFolder(req.targetFolderId());
        accessPolicy.requireEdit(target, userId);
        User actor = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        Set<String> takenNames = new HashSet<>();
        files.findActiveInFolder(target.getId()).forEach(f -> takenNames.add(f.getName()));
        folders.findChildren(target.getId()).forEach(f -> takenNames.add(f.getName()));

        int copied = 0;
        for (DriveDtos.ItemRef ref : req.items()) {
            if (ref.type().equals("folder")) {
                Folder source = getFolder(ref.id());
                accessPolicy.requireRead(source, userId);
                boolean cycle = folders.findSubtree(source.getId()).stream().anyMatch(r -> r.getId().equals(target.getId()));
                if (cycle) {
                    throw ApiException.badRequest("폴더를 자기 자신이나 하위 폴더 안으로 복사할 수 없습니다.");
                }
                String name = uniqueName(source.isRoot() ? "복사된 폴더" : source.getName(), takenNames);
                copied += copyFolderTree(source, target, name, actor);
            } else {
                FileEntity source = files.findWithFolder(ref.id()).filter(Predicate.not(FileEntity::isDeleted))
                        .orElseThrow(() -> ApiException.notFound("파일"));
                accessPolicy.requireFileRead(source, userId);
                copyFile(source, target, uniqueName(source.getName(), takenNames), actor);
                copied++;
            }
            if (copied > MAX_COPY_FILES) {
                throw ApiException.badRequest("한 번에 복사할 수 있는 파일은 " + MAX_COPY_FILES + "개까지입니다.");
            }
        }
        publishChanged(Set.of(target));
        return copied;
    }

    /** 원본 폴더 트리를 너비 우선으로 복제합니다. 폴더 목록은 CTE 1회, 파일은 IN 조회 1회로 읽습니다. */
    private int copyFolderTree(Folder sourceRoot, Folder target, String rootName, User actor) {
        List<Long> subtreeIds = folders.findSubtree(sourceRoot.getId()).stream()
                .map(FolderRepository.SubtreeRow::getId).toList();
        Map<Long, List<Folder>> childrenOf = new HashMap<>();
        for (Folder f : folders.findAllById(subtreeIds)) {
            if (!f.getId().equals(sourceRoot.getId())) {
                childrenOf.computeIfAbsent(f.getParent().getId(), k -> new java.util.ArrayList<>()).add(f);
            }
        }
        Map<Long, List<FileEntity>> filesOf = new HashMap<>();
        List<FileEntity> sourceFiles = files.findActiveWithVersionInFolders(subtreeIds);
        if (sourceFiles.size() > MAX_COPY_FILES) {
            throw ApiException.badRequest("한 번에 복사할 수 있는 파일은 " + MAX_COPY_FILES + "개까지입니다.");
        }
        sourceFiles.forEach(f -> filesOf.computeIfAbsent(f.getFolder().getId(), k -> new java.util.ArrayList<>()).add(f));

        Folder newRoot = folders.save(Folder.childOf(target, rootName, actor));
        record Pair(Folder source, Folder copy) {
        }
        Deque<Pair> queue = new ArrayDeque<>();
        queue.add(new Pair(sourceRoot, newRoot));
        int count = 0;
        while (!queue.isEmpty()) {
            Pair p = queue.poll();
            for (FileEntity f : filesOf.getOrDefault(p.source().getId(), List.of())) {
                copyFile(f, p.copy(), f.getName(), actor);
                count++;
            }
            for (Folder child : childrenOf.getOrDefault(p.source().getId(), List.of())) {
                queue.add(new Pair(child, folders.save(Folder.childOf(p.copy(), child.getName(), actor))));
            }
        }
        return count;
    }

    private void copyFile(FileEntity source, Folder target, String name, User actor) {
        FileVersion active = source.getActiveVersion();
        String key = BlobLifecycle.newFileKey();
        blobLifecycle.copyWithRollbackCleanup(active.getStoredPath(), key);
        FileEntity copy = files.save(new FileEntity(target, actor, name, active.getSize()));
        FileVersion first = versions.save(new FileVersion(copy, key, actor, active.getSize(), active.getSha256()));
        copy.activate(first);
    }

    private static void requireSameScope(Folder source, Folder target) {
        if (!source.sameScopeAs(target)) {
            throw ApiException.badRequest("다른 스토리지(개인↔팀, 팀↔팀)로는 이동할 수 없습니다. 복사를 이용하세요.");
        }
    }

    /** 같은 이름이 있으면 "이름 (1).확장자" 처럼 번호를 붙입니다. */
    static String uniqueName(String name, Set<String> taken) {
        if (taken.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (taken.add(candidate)) {
                return candidate;
            }
        }
    }

    private Folder getFolder(Long id) {
        return folders.findById(id).orElseThrow(() -> ApiException.notFound("폴더"));
    }

    private void publishChanged(Set<Folder> touched) {
        for (Folder f : touched) {
            if (f != null && f.teamId() != null) {
                events.publishEvent(new RealtimeEvents.FolderChanged(f.teamId(), f.getId()));
            }
        }
    }
}
