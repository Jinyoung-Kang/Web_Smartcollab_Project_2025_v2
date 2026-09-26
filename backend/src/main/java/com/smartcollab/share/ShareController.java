package com.smartcollab.share;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Share links", description = "공유 링크 관리 (로그인 필요)")
@RestController
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @Operation(summary = "공유 링크 만들기", description = "파일 소유자 또는 팀장만. 비밀번호·유효 기간·다운로드 횟수 제한 선택")
    @PostMapping("/api/files/{fileId}/share-links")
    public ResponseEntity<ShareDtos.LinkResponse> create(@PathVariable Long fileId,
                                                         @Valid @RequestBody ShareDtos.CreateRequest request,
                                                         @CurrentUser AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shareService.create(fileId, request, user.id()));
    }

    @GetMapping("/api/files/{fileId}/share-links")
    public List<ShareDtos.LinkResponse> list(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        return shareService.list(fileId, user.id());
    }

    @DeleteMapping("/api/share-links/{linkId}")
    public ResponseEntity<Void> revoke(@PathVariable Long linkId, @CurrentUser AuthUser user) {
        shareService.revoke(linkId, user.id());
        return ResponseEntity.noContent().build();
    }
}
