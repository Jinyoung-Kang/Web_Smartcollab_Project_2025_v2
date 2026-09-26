package com.smartcollab.file;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderNode;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.FolderTree;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.util.FileNames;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.storage.BlobLifecycle;
import com.smartcollab.storage.BlobStorage;
import com.smartcollab.storage.StoredBlob;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FileService {

    private static final int SEARCH_LIMIT = 100;

    private final FileRepository files;
    private final FileVersionRepository versions;
    private final FolderRepository folders;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final BlobLifecycle blobLifecycle;
    private final BlobStorage storage;
    private final ApplicationEventPublisher events;

    /**
     * 업로드: 저장소에 스트리밍으로 쓰면서 SHA-256 을 계산하고, 메타데이터와 첫 버전을 저장합니다.
     * DB 저장이 실패하면 {@link BlobLifecycle} 이 방금 쓴 파일을 지웁니다.
     */
    @Transactional
    public DriveDtos.ItemResponse upload(Long folderId, MultipartFile multipart, Long userId) {
        if (multipart == null || multipart.isEmpty()) {
            throw ApiException.badRequest("빈 파일은 업로드할 수 없습니다.");
        }
        Folder folder = folders.findById(folderId).orElseThrow(() -> ApiException.notFound("폴더"));
        accessPolicy.requireEdit(folder, userId);
        User uploader = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String name = FileNames.sanitizeUploadName(multipart.getOriginalFilename());

        String key = BlobLifecycle.newFileKey();
        StoredBlob blob;
        try (InputStream in = multipart.getInputStream()) {
            blob = blobLifecycle.putWithRollbackCleanup(key, in, multipart.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림을 읽지 못했습니다.", e);
        }

        FileEntity file = files.save(new FileEntity(folder, uploader, name, blob.size()));
        FileVersion first = versions.save(new FileVersion(file, key, uploader, blob.size(), blob.sha256()));
        file.activate(first);
        publishChanged(folder);
        return DriveDtos.ItemResponse.of(file);
    }

    /** 다운로드 대상 정보(트랜잭션 안에서 권한 확인). 실제 바이트 스트림은 트랜잭션 밖에서 엽니다. */
    @Transactional(readOnly = true)
    public DownloadTarget downloadTarget(Long fileId, Long userId) {
        FileEntity file = getActive(fileId);
        accessPolicy.requireFileRead(file, userId);
        FileVersion active = file.getActiveVersion();
        return new DownloadTarget(file.getName(), active.getStoredPath(), active.getSize());
    }

    public InputStream open(DownloadTarget target) {
        return storage.open(target.storageKey());
    }

    @Transactional
    public void rename(Long fileId, String newName, Long userId) {
        FileEntity file = getActive(fileId);
        accessPolicy.requireFileEdit(file, userId);
        file.rename(FileNames.validate(newName));
        publishChanged(file.getFolder());
    }

    @Transactional
    public void moveToTrash(Long fileId, Long userId) {
        FileEntity file = getActive(fileId);
        accessPolicy.requireFileDelete(file, userId);
        file.moveToTrash(users.getReferenceById(userId));
        publishChanged(file.getFolder());
    }

    /** Office 문서 미리보기용 읽기 전용 임시 URL (Azure 저장소일 때만). */
    @Transactional(readOnly = true)
    public String officePreviewUrl(Long fileId, Long userId) {
        FileEntity file = getActive(fileId);
        accessPolicy.requireFileRead(file, userId);
        if (FileNames.previewKind(file.getName()) != FileNames.PreviewKind.OFFICE) {
            throw ApiException.badRequest("Office 문서만 온라인 미리보기를 지원합니다.");
        }
        return storage.readOnlyUrl(file.getActiveVersion().getStoredPath(), Duration.ofMinutes(10))
                .orElseThrow(() -> new ApiException(ErrorCode.FEATURE_DISABLED,
                        "Office 미리보기는 클라우드(Azure) 저장소에서만 지원합니다. 파일을 내려받아 확인하세요."));
    }

    /**
     * 파일 이름 검색. 범위(개인/팀)의 폴더를 한 번에 읽고, 그 폴더들에 속한 파일을 한 번에 찾습니다 → 쿼리 2회.
     * v1 은 폴더마다 재귀적으로 쿼리해 폴더 수만큼 SQL 이 실행되었고, 팀 멤버 여부도 확인하지 않았습니다.
     */
    @Transactional(readOnly = true)
    public List<DriveDtos.SearchResult> search(String query, Long teamId, Long userId) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        if (q.length() > 100) {
            throw ApiException.badRequest("검색어는 100자 이하로 입력하세요.");
        }
        List<FolderNode> nodes;
        if (teamId != null) {
            accessPolicy.requireMember(teamId, userId);
            nodes = folders.findTeamNodes(teamId);
        } else {
            nodes = folders.findPersonalNodes(userId);
        }
        if (nodes.isEmpty()) {
            return List.of();
        }
        FolderTree tree = new FolderTree(nodes);
        String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return files.search(tree.ids(), pattern, PageRequest.of(0, SEARCH_LIMIT)).stream()
                .map(f -> new DriveDtos.SearchResult(DriveDtos.ItemResponse.of(f), f.getFolder().getId(),
                        tree.pathOf(f.getFolder().getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public DriveDtos.UsageResponse usage(Long teamId, Long userId) {
        List<FolderNode> nodes;
        if (teamId != null) {
            accessPolicy.requireMember(teamId, userId);
            nodes = folders.findTeamNodes(teamId);
        } else {
            nodes = folders.findPersonalNodes(userId);
        }
        if (nodes.isEmpty()) {
            return new DriveDtos.UsageResponse(0, 0);
        }
        StorageUsage usage = files.usageOf(nodes.stream().map(FolderNode::id).toList());
        return new DriveDtos.UsageResponse(usage.fileCount(), usage.totalBytes());
    }

    FileEntity getActive(Long fileId) {
        FileEntity file = files.findWithFolder(fileId).orElseThrow(() -> ApiException.notFound("파일"));
        if (file.isDeleted()) {
            throw ApiException.notFound("파일");
        }
        return file;
    }

    Access accessForRead(FileEntity file, Long userId) {
        return accessPolicy.requireFileRead(file, userId);
    }

    private void publishChanged(Folder folder) {
        if (folder.teamId() != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(folder.teamId(), folder.getId()));
        }
    }

    /** LIKE 패턴의 와일드카드(%, _)를 문자 그대로 검색하도록 이스케이프합니다 (escape 문자 '!'). */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public record DownloadTarget(String filename, String storageKey, long size) {
    }
}
