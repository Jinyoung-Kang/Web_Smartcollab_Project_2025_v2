package com.smartcollab.folder;

import com.smartcollab.file.DriveDtos;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Folders", description = "폴더 탐색·생성·이름 변경·삭제")
@RestController
@RequestMapping("/api/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderService folderService;

    @Operation(summary = "폴더 내용", description = "하위 폴더·파일, 경로(breadcrumb), 이 폴더에서의 내 권한을 함께 반환합니다.")
    @GetMapping("/{folderId}")
    public FolderDtos.FolderContents contents(@PathVariable Long folderId, @CurrentUser AuthUser user) {
        return folderService.contents(folderId, user.id());
    }

    @Operation(summary = "폴더 트리", description = "teamId 가 없으면 내 드라이브, 있으면 팀 스토리지 전체 트리 (쿼리 1회)")
    @GetMapping("/tree")
    public FolderDtos.FolderTreeResponse tree(@RequestParam(required = false) Long teamId, @CurrentUser AuthUser user) {
        return folderService.tree(teamId, user.id());
    }

    @PostMapping
    public ResponseEntity<DriveDtos.ItemResponse> create(@Valid @RequestBody FolderDtos.CreateFolderRequest request,
                                                         @CurrentUser AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(folderService.create(request, user.id()));
    }

    @PatchMapping("/{folderId}")
    public ResponseEntity<Void> rename(@PathVariable Long folderId, @Valid @RequestBody DriveDtos.RenameRequest request,
                                       @CurrentUser AuthUser user) {
        folderService.rename(folderId, request.name(), user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "폴더 영구 삭제", description = "하위 폴더와 파일(휴지통 포함)까지 함께 삭제됩니다.")
    @DeleteMapping("/{folderId}")
    public ResponseEntity<Void> delete(@PathVariable Long folderId, @CurrentUser AuthUser user) {
        folderService.delete(folderId, user.id());
        return ResponseEntity.noContent().build();
    }
}
