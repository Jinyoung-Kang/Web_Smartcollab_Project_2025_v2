package com.smartcollab.folder;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.file.TrashService;
import com.smartcollab.file.DriveDtos;
import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.util.FileNames;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FolderService {

    private final FolderRepository folders;
    private final FileRepository files;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final FolderDepthPolicy depthPolicy;
    private final FolderStructureLock structureLock;
    private final TrashService trash;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public DriveDtos.FolderContents contents(Long folderId, Long userId) {
        Folder folder = get(folderId);
        Access access = accessPolicy.requireRead(folder, userId);

        List<DriveDtos.ItemResponse> items = new ArrayList<>();
        folders.findChildren(folderId).stream()
                .sorted(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER))
                .map(DriveDtos.ItemResponse::of)
                .forEach(items::add);
        files.findActiveInFolder(folderId).stream()
                .sorted(Comparator.comparing(FileEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .map(DriveDtos.ItemResponse::of)
                .forEach(items::add);

        List<DriveDtos.Breadcrumb> path = folders.findPath(folderId).stream()
                .map(row -> new DriveDtos.Breadcrumb(row.getId(),
                        row.getParentId() == null ? rootName(folder) : row.getName()))
                .toList();
        DriveDtos.FolderInfo info = new DriveDtos.FolderInfo(folder.getId(),
                folder.isRoot() ? rootName(folder) : folder.getName(), folder.teamId(), folder.isRoot());
        return new DriveDtos.FolderContents(info, path, items, DriveDtos.PermissionsResponse.of(access));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriveDtos.ItemResponse create(DriveDtos.CreateFolderRequest req, Long userId) {
        structureLock.lockScopesOf(List.of(req.parentId()));   // 휴지통에 들어가는 폴더 아래에 동시에 만들지 못하게 [S-06]
        Folder parent = get(req.parentId());
        accessPolicy.requireEdit(parent, userId);
        depthPolicy.requireRoomUnder(parent, 0);
        User creator = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        Folder folder = folders.save(Folder.childOf(parent, FileNames.validate(req.name()), creator));
        publishChanged(parent);
        return DriveDtos.ItemResponse.of(folder);
    }

    @Transactional
    public void rename(Long folderId, String name, Long userId) {
        Folder folder = get(folderId);
        accessPolicy.requireEdit(folder, userId);
        if (folder.isRoot()) {
            throw ApiException.badRequest("최상위 폴더의 이름은 바꿀 수 없습니다.");
        }
        folder.rename(FileNames.validate(name));
        publishChanged(folder.getParent());
    }

    /** 폴더를 하위 폴더·파일과 함께 휴지통으로 옮깁니다(30일 뒤 자동 영구 삭제) [UX-06]. 이전에는 즉시 영구 삭제였습니다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long folderId, Long userId) {
        structureLock.lockScopesOf(List.of(folderId));
        Folder folder = get(folderId);
        accessPolicy.requireDelete(folder, userId);
        if (folder.isRoot()) {
            throw ApiException.badRequest("최상위 폴더는 삭제할 수 없습니다.");
        }
        Long teamId = folder.teamId();
        Long parentId = folder.getParent().getId();
        trash.moveFolderToTrash(folder, userId);
        if (teamId != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(teamId, parentId));
        }
    }

    @Transactional(readOnly = true)
    public DriveDtos.FolderTreeResponse tree(Long teamId, Long userId) {
        List<FolderNode> nodes;
        String rootName;
        if (teamId != null) {
            accessPolicy.requireMember(teamId, userId);
            nodes = folders.findTeamNodes(teamId);
            rootName = folders.findTeamRoot(teamId).map(Folder::getName).orElse("팀 스토리지");
        } else {
            nodes = folders.findPersonalNodes(userId);
            rootName = "내 드라이브";
        }
        return new DriveDtos.FolderTreeResponse(new FolderTree(nodes).roots(rootName));
    }

    private Folder get(Long folderId) {
        return folders.findById(folderId).orElseThrow(() -> ApiException.notFound("폴더"));
    }

    private static String rootName(Folder anyFolderInScope) {
        return anyFolderInScope.getTeam() == null ? "내 드라이브" : anyFolderInScope.getTeam().getName();
    }

    private void publishChanged(Folder folder) {
        if (folder != null && folder.teamId() != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(folder.teamId(), folder.getId()));
        }
    }
}
