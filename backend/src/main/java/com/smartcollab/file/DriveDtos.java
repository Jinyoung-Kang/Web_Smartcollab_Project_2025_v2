package com.smartcollab.file;

import com.smartcollab.access.Access;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderTree;
import com.smartcollab.global.util.FileNames;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class DriveDtos {

    private DriveDtos() {
    }

    public record ItemResponse(String type, Long id, String name, Long size, String extension, String ownerName,
                               Instant createdAt, Instant updatedAt, FileNames.PreviewKind previewKind,
                               boolean textEditable) {

        public static ItemResponse of(Folder folder) {
            return new ItemResponse("folder", folder.getId(), folder.getName(), null, null,
                    folder.getOwner().getName(), folder.getCreatedAt(), folder.getCreatedAt(), FileNames.PreviewKind.NONE,
                    false);
        }

        public static ItemResponse of(FileEntity file) {
            return new ItemResponse("file", file.getId(), file.getName(), file.getSize(),
                    FileNames.extension(file.getName()), file.getOwner().getName(), file.getCreatedAt(),
                    file.getUpdatedAt(), FileNames.previewKind(file.getName()), FileNames.isTextEditable(file.getName()));
        }
    }

    public record PermissionsResponse(boolean canEdit, boolean canDelete, boolean canInvite, boolean leader) {
        public static PermissionsResponse of(Access access) {
            return new PermissionsResponse(access.canEdit(), access.canDelete(), access.canInvite(), access.leader());
        }
    }

    public record Breadcrumb(Long id, String name) {
    }

    public record FolderInfo(Long id, String name, Long teamId, boolean root) {
    }

    public record FolderContents(FolderInfo folder, List<Breadcrumb> path, List<ItemResponse> items,
                                 PermissionsResponse permissions) {
    }

    public record FolderTreeResponse(List<FolderTree.TreeNode> roots) {
    }

    public record CreateFolderRequest(
            @NotNull(message = "상위 폴더를 지정하세요.") Long parentId,
            @NotBlank(message = "폴더 이름을 입력하세요.") @Size(max = 255, message = "이름은 255자 이하입니다.") String name) {
    }

    public record RenameRequest(
            @NotBlank(message = "새 이름을 입력하세요.") @Size(max = 255, message = "이름은 255자 이하입니다.") String name) {
    }

    public record ItemRef(
            @NotNull @Pattern(regexp = "file|folder", message = "type 은 file 또는 folder 입니다.") String type,
            @NotNull Long id) {
    }

    public record TransferRequest(
            @NotEmpty(message = "대상을 선택하세요.") @Size(max = 200, message = "한 번에 200개까지 처리할 수 있습니다.")
            List<@Valid ItemRef> items,
            @NotNull(message = "대상 폴더를 선택하세요.") Long targetFolderId) {
    }

    /** 여러 항목 삭제 [PERF-03] */
    public record DeleteRequest(
            @NotEmpty(message = "대상을 선택하세요.") @Size(max = 200, message = "한 번에 200개까지 처리할 수 있습니다.")
            List<@Valid ItemRef> items) {
    }

    /**
     * @param trashedFiles   휴지통으로 옮긴 파일 수
     * @param deletedFolders 영구 삭제한 폴더 수 (안의 파일 포함)
     */
    public record DeleteResponse(int trashedFiles, int deletedFolders) {
    }

    public record SearchResult(ItemResponse item, Long folderId, String path) {
    }

    /**
     * @param fileCount   휴지통을 뺀 파일 수
     * @param totalBytes  휴지통을 뺀 현재 버전 크기의 합
     * @param storedBytes 한도에 셈하는 실제 저장량 (옛 버전·휴지통 포함)
     * @param quotaBytes  저장 한도
     */
    public record UsageResponse(long fileCount, long totalBytes, long storedBytes, long quotaBytes) {
    }

    public record TrashItem(Long id, String name, Long size, String extension, Instant deletedAt, String deletedByName,
                            Long folderId) {
        public static TrashItem of(FileEntity f) {
            return new TrashItem(f.getId(), f.getName(), f.getSize(), FileNames.extension(f.getName()), f.getDeletedAt(),
                    f.getDeletedBy() == null ? null : f.getDeletedBy().getName(), f.getFolder().getId());
        }
    }
}
