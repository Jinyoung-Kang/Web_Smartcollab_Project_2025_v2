package com.smartcollab.file;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import com.smartcollab.global.util.FileResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "Files", description = "업로드·다운로드·미리보기·텍스트 편집·버전·검색")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final ItemDeletionService itemDeletionService;
    private final FileContentService contentService;

    @Operation(summary = "업로드", description = "multipart/form-data 의 file 파트를 folderId 폴더에 저장합니다.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DriveDtos.ItemResponse> upload(@RequestParam Long folderId,
                                                         @RequestPart("file") MultipartFile file,
                                                         @CurrentUser AuthUser user) {
        UploadSource source = new UploadSource(file.getOriginalFilename(), file.getSize(), file::getInputStream);
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.upload(folderId, source, user.id()));
    }

    @Operation(summary = "파일 정보", description = "이름·크기·미리보기 종류. 채팅에 공유된 파일처럼 폴더 목록 없이 미리 볼 때 씁니다.")
    @GetMapping("/{fileId}")
    public DriveDtos.ItemResponse get(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        return fileService.get(fileId, user.id());
    }

    @Operation(summary = "다운로드(스트리밍)")
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        FileService.DownloadTarget target = fileService.downloadTarget(fileId, user.id());
        return FileResponses.stream(fileService.open(target), target.size(), target.filename(), false);
    }

    @Operation(summary = "브라우저 미리보기", description = "이미지·PDF·텍스트만 inline 으로, 그 외 형식은 첨부파일로 응답합니다.")
    @GetMapping("/{fileId}/view")
    public ResponseEntity<Resource> view(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        FileService.DownloadTarget target = fileService.downloadTarget(fileId, user.id());
        return FileResponses.stream(fileService.open(target), target.size(), target.filename(), true);
    }

    @Operation(summary = "Office 문서 미리보기 URL", description = "Azure 저장소의 10분짜리 읽기 전용 SAS URL")
    @GetMapping("/{fileId}/office-preview-url")
    public Map<String, String> officePreviewUrl(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        return Map.of("url", fileService.officePreviewUrl(fileId, user.id()));
    }

    @PatchMapping("/{fileId}")
    public ResponseEntity<Void> rename(@PathVariable Long fileId, @Valid @RequestBody DriveDtos.RenameRequest request,
                                       @CurrentUser AuthUser user) {
        fileService.rename(fileId, request.name(), user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "휴지통으로 이동")
    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> trash(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        itemDeletionService.delete(new DriveDtos.DeleteRequest(List.of(new DriveDtos.ItemRef("file", fileId))), user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "텍스트 내용", description = "현재 버전의 내용과 버전 ID(저장 시 충돌 검사용)")
    @GetMapping("/{fileId}/content")
    public FileContentService.TextContent content(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        return contentService.readText(fileId, user.id());
    }

    @Operation(summary = "텍스트 저장(새 버전)", description = "baseVersionId 가 현재 버전과 다르면 409 EDIT_CONFLICT")
    @PutMapping("/{fileId}/content")
    public FileContentService.SaveResult save(@PathVariable Long fileId, @Valid @RequestBody SaveTextRequest request,
                                              @CurrentUser AuthUser user) {
        return contentService.saveText(fileId, request.content(), request.baseVersionId(), user.id());
    }

    @GetMapping("/{fileId}/versions")
    public List<FileContentService.VersionResponse> versions(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        return contentService.history(fileId, user.id());
    }

    @PostMapping("/{fileId}/versions/{versionId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long fileId, @PathVariable Long versionId,
                                        @CurrentUser AuthUser user) {
        contentService.restore(fileId, versionId, user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "파일 이름 검색", description = "teamId 가 없으면 내 드라이브, 있으면 팀 스토리지에서 검색 (최대 100건)")
    @GetMapping("/search")
    public List<DriveDtos.SearchResult> search(@RequestParam("q") String query,
                                               @RequestParam(required = false) Long teamId,
                                               @CurrentUser AuthUser user) {
        return fileService.search(query, teamId, user.id());
    }

    @Operation(summary = "저장 공간 사용량")
    @GetMapping("/usage")
    public DriveDtos.UsageResponse usage(@RequestParam(required = false) Long teamId, @CurrentUser AuthUser user) {
        return fileService.usage(teamId, user.id());
    }

    public record SaveTextRequest(@NotNull(message = "내용이 없습니다.") String content,
                                  @NotNull(message = "기준 버전이 없습니다.") Long baseVersionId) {
    }
}
