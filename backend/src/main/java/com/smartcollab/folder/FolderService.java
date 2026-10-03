package com.smartcollab.folder;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.access.PermissionsResponse;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.file.DriveDtos;
import com.smartcollab.file.FileRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.util.FileNames;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public FolderDtos.FolderContents contents(Long folderId, Long userId) {
        return contents(folderId, FolderListing.Request.DEFAULT, userId);
    }

    /**
     * 폴더 내용을 정렬해 한 묶음씩 돌려줍니다 [IMP-02]. 목록 열만 읽고(엔티티를 만들지 않음) 정렬한 뒤 요청한 위치부터 limit 개를 줍니다.
     * 이전에는 항목 전부를 돌려줘 1만 개 폴더의 응답이 2.31MB 였습니다.
     */
    @Transactional(readOnly = true)
    public FolderDtos.FolderContents contents(Long folderId, FolderListing.Request request, Long userId) {
        Folder folder = get(folderId);
        Access access = accessPolicy.requireRead(folder, userId);

        List<DriveDtos.ItemResponse> all = new ArrayList<>();
        folders.findListedChildren(folderId).forEach(f -> all.add(DriveDtos.ItemResponse.of(f)));
        files.findListedInFolder(folderId).forEach(f -> all.add(DriveDtos.ItemResponse.of(f)));
        List<DriveDtos.ItemResponse> sorted = FolderListing.sort(all, request);
        int from = Math.min(request.offset(), sorted.size());
        int to = Math.min(from + request.limit(), sorted.size());
        String nextCursor = to < sorted.size() ? FolderListing.encode(to) : null;

        List<FolderDtos.Breadcrumb> path = folders.findPath(folderId).stream()
                .map(row -> new FolderDtos.Breadcrumb(row.getId(),
                        row.getParentId() == null ? rootName(folder) : row.getName()))
                .toList();
        FolderDtos.FolderInfo info = new FolderDtos.FolderInfo(folder.getId(),
                folder.isRoot() ? rootName(folder) : folder.getName(), folder.teamId(), folder.isRoot());
        return new FolderDtos.FolderContents(info, path, List.copyOf(sorted.subList(from, to)), PermissionsResponse.of(access),
                nextCursor, sorted.size());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriveDtos.ItemResponse create(FolderDtos.CreateFolderRequest req, Long userId) {
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

    @Transactional(readOnly = true)
    public FolderDtos.FolderTreeResponse tree(Long teamId, Long userId) {
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
        return new FolderDtos.FolderTreeResponse(new FolderTree(nodes).roots(rootName));
    }

    private Folder get(Long folderId) {
        return folders.findById(folderId).orElseThrow(() -> ApiException.notFound("폴더"));
    }

    private static String rootName(Folder anyFolderInScope) {
        return anyFolderInScope.getTeam() == null ? "내 드라이브" : anyFolderInScope.getTeam().getName();
    }

    private void publishChanged(Folder folder) {
        if (folder != null && folder.teamId() != null) {
            events.publishEvent(new ChangeEvents.FolderChanged(folder.teamId(), folder.getId()));
        }
    }
}
