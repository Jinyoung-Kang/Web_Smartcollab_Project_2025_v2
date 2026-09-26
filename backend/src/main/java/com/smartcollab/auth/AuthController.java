package com.smartcollab.auth;

import com.smartcollab.global.security.AuthCookies;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.ClientIp;
import com.smartcollab.global.security.CurrentUser;
import com.smartcollab.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원가입·로그인")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookies cookies;

    @Operation(summary = "CSRF 토큰 발급", description = "XSRF-TOKEN 쿠키를 설정하고 같은 값을 반환합니다. SPA 가 시작할 때 호출합니다.")
    @GetMapping("/csrf")
    public AuthDtos.CsrfResponse csrf(CsrfToken token) {
        return new AuthDtos.CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @Operation(summary = "회원가입 후 바로 로그인")
    @PostMapping("/signup")
    public ResponseEntity<AuthDtos.MeResponse> signUp(@Valid @RequestBody AuthDtos.SignUpRequest request) {
        User user = authService.signUp(request);
        return withLoginCookie(HttpStatus.CREATED, user);
    }

    @Operation(summary = "로그인", description = "성공하면 HttpOnly 인증 쿠키(SC_AUTH)를 설정합니다. IP 당 분당, 계정당 10분 시도 횟수가 제한됩니다.")
    @PostMapping("/login")
    public ResponseEntity<AuthDtos.MeResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                     HttpServletRequest http) {
        User user = authService.authenticate(request.username(), request.password(), ClientIp.of(http));
        return withLoginCookie(HttpStatus.OK, user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @GetMapping("/me")
    public AuthDtos.MeResponse me(@CurrentUser AuthUser user) {
        return authService.me(user.id());
    }

    private ResponseEntity<AuthDtos.MeResponse> withLoginCookie(HttpStatus status, User user) {
        String token = authService.issueToken(user);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.issue(token, authService.tokenTtl()).toString())
                .body(authService.me(user.getId()));
    }
}
