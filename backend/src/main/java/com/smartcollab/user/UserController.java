package com.smartcollab.user;

import com.smartcollab.global.security.AuthCookies;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "계정")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AccountService accountService;
    private final AuthCookies cookies;

    @Operation(summary = "회원 탈퇴", description = "비밀번호를 다시 확인한 뒤 개인 데이터를 삭제하고 로그아웃합니다.")
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(@RequestBody DeleteAccountRequest request, @CurrentUser AuthUser user) {
        accountService.deleteAccount(user.id(), request.password());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    public record DeleteAccountRequest(String password) {
    }
}
