package com.smartcollab.folder;

import com.smartcollab.access.PermissionsResponse;
import com.smartcollab.file.DriveDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 폴더 API(/api/folders)의 요청·응답 [A-04]. 폴더 안의 항목(폴더·파일)은 드라이브 공통 형식 {@link DriveDtos.ItemResponse} 입니다. */
public final class FolderDtos {

    private FolderDtos() {
    }

    public record Breadcrumb(Long id, String name) {
    }

    public record FolderInfo(Long id, String name, Long teamId, boolean root) {
    }

    public record FolderContents(FolderInfo folder, List<Breadcrumb> path, List<DriveDtos.ItemResponse> items,
                                 PermissionsResponse permissions) {
    }

    public record FolderTreeResponse(List<FolderTree.TreeNode> roots) {
    }

    public record CreateFolderRequest(
            @NotNull(message = "상위 폴더를 지정하세요.") Long parentId,
            @NotBlank(message = "폴더 이름을 입력하세요.") @Size(max = 255, message = "이름은 255자 이하입니다.") String name) {
    }
}
