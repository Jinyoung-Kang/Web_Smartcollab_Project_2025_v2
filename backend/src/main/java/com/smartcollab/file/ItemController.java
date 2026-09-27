package com.smartcollab.file;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Items", description = "파일·폴더 일괄 이동/복사/삭제")
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemTransferService transferService;
    private final ItemDeletionService deletionService;

    @Operation(summary = "이동", description = "같은 스토리지 안에서만 이동할 수 있습니다.")
    @PostMapping("/move")
    public ResponseEntity<Void> move(@Valid @RequestBody DriveDtos.TransferRequest request, @CurrentUser AuthUser user) {
        transferService.move(request, user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "복사", description = "폴더는 하위 구조까지 복사합니다. 스토리지 간 복사도 가능합니다.")
    @PostMapping("/copy")
    public Map<String, Integer> copy(@Valid @RequestBody DriveDtos.TransferRequest request, @CurrentUser AuthUser user) {
        return Map.of("copiedFiles", transferService.copy(request, user.id()));
    }

    @Operation(summary = "삭제", description = "파일은 휴지통으로, 폴더는 안의 파일까지 영구 삭제합니다. 하나라도 지울 수 없으면 아무것도 지우지 않습니다.")
    @PostMapping("/delete")
    public DriveDtos.DeleteResponse delete(@Valid @RequestBody DriveDtos.DeleteRequest request, @CurrentUser AuthUser user) {
        return deletionService.delete(request, user.id());
    }
}
