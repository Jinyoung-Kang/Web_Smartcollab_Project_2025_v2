package com.smartcollab.file;

import com.smartcollab.folder.Folder;
import com.smartcollab.folder.ListedFolder;
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

        public static ItemResponse of(ListedFolder folder) {
            return new ItemResponse("folder", folder.id(), folder.name(), null, null, folder.ownerName(),
                    folder.createdAt(), folder.createdAt(), FileNames.PreviewKind.NONE, false);
        }

        public static ItemResponse of(ListedFile file) {
            return new ItemResponse("file", file.id(), file.name(), file.size(), FileNames.extension(file.name()),
                    file.ownerName(), file.createdAt(), file.updatedAt(), FileNames.previewKind(file.name()),
                    FileNames.isTextEditable(file.name()));
        }

        public static ItemResponse of(FileEntity file) {
            return new ItemResponse("file", file.getId(), file.getName(), file.getSize(),
                    FileNames.extension(file.getName()), file.getOwner().getName(), file.getCreatedAt(),
                    file.getUpdatedAt(), FileNames.previewKind(file.getName()), FileNames.isTextEditable(file.getName()));
        }
    }

    public record RenameRequest(
            @NotBlank(message = "새 이름을 입력하세요.") @Size(max = 255, message = "이름은 255자 이하입니다.") String name) {
    }

    public record ItemRef(
            @NotNull @Pattern(regexp = "file|folder", message = "type 은 file 또는 folder 입니다.") String type,
            @NotNull Long id) {
    }

    /** items 의 null 항목은 400 으로 거절합니다(이전에는 NullPointerException 으로 500) [QA-04] */
    public record TransferRequest(
            @NotEmpty(message = "대상을 선택하세요.") @Size(max = 200, message = "한 번에 200개까지 처리할 수 있습니다.")
            List<@NotNull(message = "빈 항목이 있습니다.") @Valid ItemRef> items,
            @NotNull(message = "대상 폴더를 선택하세요.") Long targetFolderId) {
    }

    /** 여러 항목 삭제 [PERF-03] */
    public record DeleteRequest(
            @NotEmpty(message = "대상을 선택하세요.") @Size(max = 200, message = "한 번에 200개까지 처리할 수 있습니다.")
            List<@NotNull(message = "빈 항목이 있습니다.") @Valid ItemRef> items) {
    }

    /**
     * @param trashedFiles   휴지통으로 옮긴 파일 수
     * @param trashedFolders 휴지통으로 옮긴 폴더 수 (안의 폴더·파일과 함께) [UX-06]
     */
    public record DeleteResponse(int trashedFiles, int trashedFolders) {
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

    /**
     * 휴지통 항목 (파일 또는 폴더 트리) [UX-06].
     * @param folderId  원래 있던 상위 폴더
     * @param fileCount 폴더일 때 안에 든 파일 수 (따로 휴지통에 넣은 파일·하위 폴더는 빼고), 파일이면 null
     * @param size      파일 크기, 폴더면 안에 든 파일 크기의 합
     */
    public record TrashItem(String type, Long id, String name, Long size, String extension, Instant deletedAt,
                            String deletedByName, Long folderId, Long fileCount) {
        public static TrashItem of(FileEntity f) {
            return new TrashItem("file", f.getId(), f.getName(), f.getSize(), FileNames.extension(f.getName()), f.getDeletedAt(),
                    f.getDeletedBy() == null ? null : f.getDeletedBy().getName(), f.getFolder().getId(), null);
        }

        public static TrashItem of(Folder folder, TrashedTreeSize size) {
            return new TrashItem("folder", folder.getId(), folder.getName(), size == null ? 0 : size.totalBytes(), null,
                    folder.getDeletedAt(), folder.getDeletedBy() == null ? null : folder.getDeletedBy().getName(),
                    folder.getParent().getId(), size == null ? 0 : size.fileCount());
        }
    }

    /**
     * 휴지통에서 폴더를 복원한 결과 [UX-06].
     * @param folderId  복원된 위치(상위 폴더)
     * @param relocated 원래 상위 폴더가 휴지통에 있어 최상위 폴더로 복원했으면 true
     */
    public record RestoreResponse(Long folderId, boolean relocated) {
    }
}
