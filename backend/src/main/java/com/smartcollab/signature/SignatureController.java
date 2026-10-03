package com.smartcollab.signature;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서명 API. 주소는 파일 아래(/api/files/{id}/signatures) 그대로입니다. */
@Tag(name = "Files", description = "업로드·다운로드·미리보기·텍스트 편집·버전·검색")
@RestController
@RequiredArgsConstructor
public class SignatureController {

    private final SignatureService signatureService;

    @Operation(summary = "현재 버전에 서명", description = "개인 파일은 소유자, 팀 파일은 팀장만 가능")
    @PostMapping("/api/files/{fileId}/signatures")
    public ResponseEntity<Void> sign(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        signatureService.sign(fileId, user.id());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
