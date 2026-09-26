package com.smartcollab.file;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Trash", description = "휴지통 (개인/팀)")
@RestController
@RequestMapping("/api/trash")
@RequiredArgsConstructor
public class TrashController {

    private final TrashService trashService;

    @GetMapping
    public List<DriveDtos.TrashItem> list(@RequestParam(required = false) Long teamId, @CurrentUser AuthUser user) {
        return trashService.list(teamId, user.id());
    }

    @PostMapping("/{fileId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        trashService.restore(fileId, user.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> deletePermanently(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        trashService.deletePermanently(fileId, user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "휴지통 비우기")
    @DeleteMapping
    public Map<String, Integer> empty(@RequestParam(required = false) Long teamId, @CurrentUser AuthUser user) {
        return Map.of("deleted", trashService.empty(teamId, user.id()));
    }
}
