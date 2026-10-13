package com.smartcollab.admin;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "관리 전용 계정의 계정 목록·삭제 (ADR-0015)")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "계정 목록", description = "아이디·이름·소속 팀·개인 드라이브 사용량·가입일 (가입일 최신순). 파일·채팅 정보는 없습니다.")
    @GetMapping("/users")
    public AdminDtos.AccountPage accounts(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @CurrentUser AuthUser user) {
        return adminService.accounts(page, size, user.id());
    }

    @Operation(summary = "계정 삭제(탈퇴 처리)",
            description = "개인 드라이브를 영구 삭제하고 팀에서 뺍니다. 팀장인 팀은 가장 먼저 들어온 멤버에게 넘기고, 멤버가 없으면 삭제합니다.")
    @DeleteMapping("/users/{userId}")
    public AdminDtos.DeletionResult delete(@PathVariable Long userId, @CurrentUser AuthUser user) {
        return adminService.deleteAccount(userId, user.id());
    }
}
