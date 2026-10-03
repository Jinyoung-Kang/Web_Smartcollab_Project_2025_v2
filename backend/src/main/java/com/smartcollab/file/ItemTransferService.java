package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderDepthPolicy;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.FolderStructureLock;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.storage.BlobLifecycle;
import com.smartcollab.storage.BlobStorage;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * </ul>
 * 이동은 DB 만 바꾸므로 한 트랜잭션, 복사는 저장소 입출력이 있어 {@link #copy} 에서 단계를 나눕니다.</p>
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
    private final BlobStorage storage;
    private final ApplicationEventPublisher events;
    private final TransactionRunner tx;
    private final StorageQuota quota;
    private final FolderDepthPolicy depthPolicy;
    private final FolderStructureLock structureLock;
    private final AppProperties props;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void move(DriveDtos.TransferRequest req, Long userId) {
        // 순환 검사·휴지통 확인을 최신 데이터로 하도록 저장 공간을 먼저 잠급니다(A→B·B→A 동시 이동 순환) [S-06]
        List<Long> lockIds = new ArrayList<>(List.of(req.targetFolderId()));
        req.items().stream().filter(r -> r.type().equals("folder")).forEach(r -> lockIds.add(r.id()));
        structureLock.lockScopesOf(lockIds);
        Folder target = getFolder(req.targetFolderId());
        accessPolicy.requireEdit(target, userId);
        Set<Folder> touched = new LinkedHashSet<>();
        touched.add(target);

        for (DriveDtos.ItemRef ref : req.items().stream().distinct().toList()) {
            if (ref.type().equals("folder")) {
                Folder folder = getFolder(ref.id());
                if (folder.isRoot()) {
                    throw ApiException.badRequest("최상위 폴더는 이동할 수 없습니다.");
                }
                accessPolicy.requireRead(folder, userId);   // 휴지통의 폴더는 없는 것처럼 [UX-06]
                accessPolicy.requireEdit(folder.getParent(), userId);
                requireSameScope(folder, target);
                List<FolderRepository.SubtreeRow> subtree = folders.findSubtree(folder.getId());
                if (subtree.stream().anyMatch(r -> r.getId().equals(target.getId()))) {
                    throw ApiException.badRequest("폴더를 자기 자신이나 하위 폴더 안으로 옮길 수 없습니다.");
                }
                depthPolicy.requireRoomUnder(target, FolderDepthPolicy.heightOf(subtree));
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

    /**
     * 복사. 저장소 복사(Azure 라면 서버 측 복사 요청과 완료 대기)는 DB 트랜잭션 밖에서 하여 그동안 커넥션을 붙잡지 않습니다 [PERF-01].
     * <ol>
     *   <li>짧은 읽기 트랜잭션: 권한·순환·개수 확인 후 만들 폴더·파일과 새 저장소 키를 계획</li>
     *   <li>트랜잭션 밖: 계획한 파일을 저장소에서 복사</li>
     *   <li>짧은 쓰기 트랜잭션: 대상 폴더와 편집 권한을 다시 확인하고 폴더·파일·첫 버전 저장</li>
     * </ol>
     * 2·3단계가 실패하면 이미 복사한 파일을 지웁니다.
     */
    public int copy(DriveDtos.TransferRequest req, Long userId) {
        CopyPlan plan = tx.readOnly(() -> plan(req, userId));
        List<String> copied = new ArrayList<>();
        try {
            for (FileCopy file : plan.allFiles()) {
                storage.copy(file.sourceKey(), file.targetKey());
                copied.add(file.targetKey());
            }
            tx.writeReadCommitted(() -> {
                apply(plan, userId);
                return null;
            });
        } catch (RuntimeException e) {
            blobLifecycle.discard(copied);
            throw e;
        }
        return plan.fileCount();
    }

    private CopyPlan plan(DriveDtos.TransferRequest req, Long userId) {
        Folder target = getFolder(req.targetFolderId());
        accessPolicy.requireEdit(target, userId);
        Set<String> takenNames = new HashSet<>();
        files.findActiveInFolder(target.getId()).forEach(f -> takenNames.add(f.getName()));
        folders.findChildren(target.getId()).forEach(f -> takenNames.add(f.getName()));

        List<FolderCopy> folderCopies = new ArrayList<>();
        List<FileCopy> fileCopies = new ArrayList<>();
        int count = 0;
        int folderCount = 0;
        // 같은 항목을 여러 번 넣어 복사본을 불리지 못하게 중복을 없앱니다 [S-04]
        for (DriveDtos.ItemRef ref : req.items().stream().distinct().toList()) {
            if (ref.type().equals("folder")) {
                Folder source = getFolder(ref.id());
                accessPolicy.requireRead(source, userId);
                List<FolderRepository.SubtreeRow> subtree = folders.findSubtree(source.getId());
                if (subtree.stream().anyMatch(r -> r.getId().equals(target.getId()))) {
                    throw ApiException.badRequest("폴더를 자기 자신이나 하위 폴더 안으로 복사할 수 없습니다.");
                }
                // 엔티티를 읽기 전에 개수부터 확인합니다 (파일 수만 세던 때는 요청 몇 번으로 폴더 수백만 개를 만들 수 있었음) [S-04]
                folderCount += subtree.size();
                if (folderCount > props.files().maxCopyFolders()) {
                    throw ApiException.badRequest("한 번에 복사할 수 있는 폴더는 " + props.files().maxCopyFolders() + "개까지입니다.");
                }
                depthPolicy.requireRoomUnder(target, FolderDepthPolicy.heightOf(subtree));
                FolderCopy tree = planFolderTree(source, subtree, uniqueName(source.isRoot() ? "복사된 폴더" : source.getName(), takenNames));
                folderCopies.add(tree);
                count += tree.fileCount();
            } else {
                FileEntity source = files.findWithFolder(ref.id()).filter(Predicate.not(FileEntity::isDeleted))
                        .orElseThrow(() -> ApiException.notFound("파일"));
                accessPolicy.requireFileRead(source, userId);
                fileCopies.add(FileCopy.of(source, uniqueName(source.getName(), takenNames)));
                count++;
            }
            if (count > MAX_COPY_FILES) {
                throw ApiException.badRequest("한 번에 복사할 수 있는 파일은 " + MAX_COPY_FILES + "개까지입니다.");
            }
        }
        CopyPlan plan = new CopyPlan(target.getId(), folderCopies, fileCopies, count);
        quota.checkRoom(StorageQuota.Scope.of(target), plan.totalBytes());
        return plan;
    }

    /** 원본 폴더 트리를 너비 우선으로 계획합니다. 폴더 목록은 CTE 1회, 파일은 IN 조회 1회로 읽습니다. */
    private FolderCopy planFolderTree(Folder sourceRoot, List<FolderRepository.SubtreeRow> subtree, String rootName) {
        List<Long> subtreeIds = subtree.stream().map(FolderRepository.SubtreeRow::getId).toList();
        Map<Long, List<Folder>> childrenOf = new HashMap<>();
        List<Long> copiedFolderIds = new ArrayList<>();
        for (Folder f : folders.findAllById(subtreeIds)) {
            if (f.isInTrash()) continue;   // 따로 휴지통에 넣은 하위 폴더(와 그 아래)는 복사하지 않음 [UX-06]
            copiedFolderIds.add(f.getId());
            if (!f.getId().equals(sourceRoot.getId())) {
                childrenOf.computeIfAbsent(f.getParent().getId(), k -> new ArrayList<>()).add(f);
            }
        }
        List<FileEntity> sourceFiles = files.findActiveWithVersionInFolders(copiedFolderIds);
        if (sourceFiles.size() > MAX_COPY_FILES) {
            throw ApiException.badRequest("한 번에 복사할 수 있는 파일은 " + MAX_COPY_FILES + "개까지입니다.");
        }
        Map<Long, List<FileEntity>> filesOf = new HashMap<>();
        sourceFiles.forEach(f -> filesOf.computeIfAbsent(f.getFolder().getId(), k -> new ArrayList<>()).add(f));

        FolderCopy root = new FolderCopy(rootName);
        record Pair(Folder source, FolderCopy copy) {
        }
        Deque<Pair> queue = new ArrayDeque<>();
        queue.add(new Pair(sourceRoot, root));
        while (!queue.isEmpty()) {
            Pair p = queue.poll();
            for (FileEntity f : filesOf.getOrDefault(p.source().getId(), List.of())) {
                p.copy().files().add(FileCopy.of(f, f.getName()));
            }
            for (Folder child : childrenOf.getOrDefault(p.source().getId(), List.of())) {
                FolderCopy childCopy = new FolderCopy(child.getName());
                p.copy().children().add(childCopy);
                queue.add(new Pair(child, childCopy));
            }
        }
        return root;
    }

    private void apply(CopyPlan plan, Long userId) {
        structureLock.lockScopesOf(List.of(plan.targetFolderId()));   // 대상이 그 사이 휴지통에 들어갔으면 아래 권한 확인에서 404 [S-06]
        Folder target = getFolder(plan.targetFolderId());
        accessPolicy.requireEdit(target, userId);
        quota.lockAndCheckRoom(StorageQuota.Scope.of(target), plan.totalBytes());
        User actor = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        plan.files().forEach(f -> saveFile(f, target, actor));
        record Pair(FolderCopy copy, Folder parent) {
        }
        Deque<Pair> queue = new ArrayDeque<>();
        plan.folders().forEach(f -> queue.add(new Pair(f, target)));
        while (!queue.isEmpty()) {
            Pair p = queue.poll();
            Folder created = folders.save(Folder.childOf(p.parent(), p.copy().name(), actor));
            p.copy().files().forEach(f -> saveFile(f, created, actor));
            p.copy().children().forEach(child -> queue.add(new Pair(child, created)));
        }
        publishChanged(Set.of(target));
    }

    private void saveFile(FileCopy source, Folder target, User actor) {
        FileEntity copy = files.save(new FileEntity(target, actor, source.name(), source.size()));
        FileVersion first = versions.save(new FileVersion(copy, source.targetKey(), actor, source.size(), source.sha256()));
        copy.activate(first);
    }

    /** 복사할 파일 하나: 원본 저장소 키와 복사본에 쓸 새 키 */
    private record FileCopy(String sourceKey, String targetKey, String name, long size, String sha256) {
        static FileCopy of(FileEntity source, String name) {
            FileVersion active = source.getActiveVersion();
            return new FileCopy(active.getStoredPath(), BlobLifecycle.newFileKey(), name, active.getSize(), active.getSha256());
        }
    }

    /** 새로 만들 폴더와 그 안의 파일·하위 폴더 */
    private record FolderCopy(String name, List<FolderCopy> children, List<FileCopy> files) {
        FolderCopy(String name) {
            this(name, new ArrayList<>(), new ArrayList<>());
        }

        int fileCount() {
            int count = 0;
            Deque<FolderCopy> stack = new ArrayDeque<>(List.of(this));
            while (!stack.isEmpty()) {
                FolderCopy f = stack.pop();
                count += f.files().size();
                stack.addAll(f.children());
            }
            return count;
        }
    }

    private record CopyPlan(Long targetFolderId, List<FolderCopy> folders, List<FileCopy> files, int fileCount) {
        long totalBytes() {
            return allFiles().stream().mapToLong(FileCopy::size).sum();
        }

        List<FileCopy> allFiles() {
            List<FileCopy> all = new ArrayList<>(files);
            Deque<FolderCopy> stack = new ArrayDeque<>(folders);
            while (!stack.isEmpty()) {
                FolderCopy f = stack.pop();
                all.addAll(f.files());
                stack.addAll(f.children());
            }
            return all;
        }
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
                events.publishEvent(new ChangeEvents.FolderChanged(f.teamId(), f.getId()));
            }
        }
    }
}
