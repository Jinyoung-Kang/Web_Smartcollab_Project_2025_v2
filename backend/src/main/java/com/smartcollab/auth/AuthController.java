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
import org.springframework.security.web.csrf.DeferredCsrfToken;
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

    /**
     * CSRF 토큰 발급. 쿠키와 같은 원래 값을 돌려줘 그대로 X-XSRF-TOKEN 헤더에 쓸 수 있게 합니다.
     * <p>컨트롤러 인자로 받는 {@link CsrfToken} 은 BREACH 방어용으로 매번 가린(masked) 값이라, 헤더로 보내면 거절됩니다
     * (SPA 방식은 헤더를 원래 값으로 비교). 이 응답에는 사용자 입력이 섞이지 않아 원래 값을 돌려줘도 BREACH 전제가 성립하지 않습니다.</p>
     */
    @Operation(summary = "CSRF 토큰 발급", description = "XSRF-TOKEN 쿠키를 설정하고 같은 값을 반환합니다. 변경 요청에 X-XSRF-TOKEN 헤더로 보내세요.")
    @GetMapping("/csrf")
    public AuthDtos.CsrfResponse csrf(HttpServletRequest request) {
        DeferredCsrfToken deferred = (DeferredCsrfToken) request.getAttribute(DeferredCsrfToken.class.getName());
        CsrfToken token = deferred.get();
        return new AuthDtos.CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @Operation(summary = "회원가입 후 바로 로그인", description = "IP 당 시간당 가입 횟수가 제한됩니다.")
    @PostMapping("/signup")
    public ResponseEntity<AuthDtos.MeResponse> signUp(@Valid @RequestBody AuthDtos.SignUpRequest request,
                                                      HttpServletRequest http) {
        AuthService.Account account = authService.signUp(request, ClientIp.of(http));
        return withLoginCookie(HttpStatus.CREATED, account);
    }

    @Operation(summary = "로그인", description = "성공하면 HttpOnly 인증 쿠키(SC_AUTH)를 설정합니다. IP 당 분당, 계정당 10분 시도 횟수가 제한됩니다.")
    @PostMapping("/login")
    public ResponseEntity<AuthDtos.MeResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                     HttpServletRequest http) {
        AuthService.Account account = authService.authenticate(request.username(), request.password(), ClientIp.of(http));
        return withLoginCookie(HttpStatus.OK, account);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearCsrf().toString())
                .build();
    }

    @GetMapping("/me")
    public AuthDtos.MeResponse me(@CurrentUser AuthUser user) {
        return authService.me(user.id());
    }

    private ResponseEntity<AuthDtos.MeResponse> withLoginCookie(HttpStatus status, AuthService.Account account) {
        String token = authService.issueToken(account);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.issue(token, authService.tokenTtl()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearCsrf().toString())
                .body(authService.me(account.id()));
    }
}
