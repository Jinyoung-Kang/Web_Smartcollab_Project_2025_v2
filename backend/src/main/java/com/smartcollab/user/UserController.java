package com.smartcollab.user;

import com.smartcollab.global.security.AuthCookies;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 비밀번호 재확인이 필요해 본문을 받으므로 POST 로 둡니다 [ARC-03]. DELETE 요청의 본문은 HTTP 에 정의된 의미가 없어
     * 일부 프록시·클라이언트가 버리거나 거절합니다. 로그아웃처럼 인증 쿠키와 CSRF 쿠키를 함께 지웁니다.
     */
    @Operation(summary = "회원 탈퇴", description = "비밀번호를 다시 확인한 뒤 개인 데이터를 삭제하고 로그아웃합니다.")
    @PostMapping("/me/delete")
    public ResponseEntity<Void> deleteMe(@RequestBody DeleteAccountRequest request, @CurrentUser AuthUser user) {
        accountService.deleteAccount(user.id(), request.password());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearCsrf().toString())
                .build();
    }

    public record DeleteAccountRequest(String password) {
    }
}
