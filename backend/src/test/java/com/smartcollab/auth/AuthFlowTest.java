package com.smartcollab.auth;

import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.user.SystemAccountInitializer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends IntegrationTest {

    @Autowired
    SlidingWindowRateLimiter rateLimiter;

    @Test
    @DisplayName("회원가입하면 HttpOnly·SameSite=Strict 인증 쿠키가 발급되고 개인 루트 폴더가 생긴다")
    void signUpIssuesHttpOnlyCookie() throws Exception {
        Api.Session s = api().signUp("alice");
        assertThat(s.cookie).isNotNull();
        assertThat(s.cookie.isHttpOnly()).isTrue();
        s.get("/api/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(s.username))
                .andExpect(jsonPath("$.rootFolderId").value(s.rootFolderId));
    }

    @Test
    @DisplayName("[v1 버그] 이메일 없이 가입한 사용자가 둘 이상이어도 가입된다 (빈 문자열 이메일 UNIQUE 충돌)")
    void blankEmailDoesNotCollide() throws Exception {
        for (int i = 0; i < 2; i++) {
            String username = "noemail" + UUID.randomUUID().toString().substring(0, 6);
            api().perform(MockMvcRequestBuilders.post("/api/auth/signup").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(api().toJson(Map.of("username", username, "password", "abcd1234",
                                    "passwordConfirm", "abcd1234", "name", "무이메일", "email", ""))))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("로그인 실패는 401 + 표준 오류 형식(ProblemDetail)으로 응답한다")
    void loginFailureIsProblemDetail() throws Exception {
        Api.Session s = api().signUp("bob");
        api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", s.username, "password", "wrong-pass1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value(containsString("일치하지 않습니다")));
    }

    @Test
    @DisplayName("로그인 성공 시 쿠키 발급, 로그아웃 시 쿠키 삭제")
    void loginAndLogout() throws Exception {
        Api.Session s = api().signUp("carol");
        Cookie issued = api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", s.username, "password", Api.PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("SC_AUTH", true))
                .andReturn().getResponse().getCookie("SC_AUTH");
        assertThat(issued).isNotNull();
        s.post("/api/auth/logout").andExpect(status().isNoContent()).andExpect(cookie().maxAge("SC_AUTH", 0));
    }

    @Test
    @DisplayName("[v1 보안] 시스템 계정(deleted_user)으로는 로그인할 수 없다")
    void systemAccountCannotLogin() throws Exception {
        api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", SystemAccountInitializer.USERNAME,
                                "password", "a_very_long_and_unusable_password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 없이 보호된 API를 호출하면 401, 위조된 쿠키는 지워진다")
    void unauthenticatedGets401() throws Exception {
        api().perform(MockMvcRequestBuilders.get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        api().perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(new Cookie("SC_AUTH", "forged.token.value")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("SC_AUTH=;")));
    }

    @Test
    @DisplayName("CSRF 토큰 없이 상태를 바꾸는 요청은 403")
    void mutationWithoutCsrfIsRejected() throws Exception {
        Api.Session s = api().signUp("dave");
        api().perform(MockMvcRequestBuilders.post("/api/folders").cookie(s.cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("parentId", s.rootFolderId, "name", "x"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("같은 IP의 로그인 시도가 한도를 넘으면 429")
    void loginIsRateLimited() throws Exception {
        String ip = "10.9.8." + (int) (Math.random() * 200);
        // 테스트 설정의 한도(분당 1000회)를 미리 채워 두고 실제 HTTP 요청이 막히는지 확인
        while (rateLimiter.tryAcquire("login:" + ip, 1000, Duration.ofMinutes(1))) {
            // fill
        }
        api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("[SEC-01] 한 계정에 대한 시도가 한도를 넘으면 IP 와 무관하게, 비밀번호가 맞아도 429")
    void loginIsRateLimitedPerAccount() throws Exception {
        Api.Session s = api().signUp("acclimit");
        while (rateLimiter.tryAcquire("login-account:" + s.username.toLowerCase(), 1000, Duration.ofMinutes(10))) {
            // 테스트 설정의 계정 단위 한도(10분 1000회)를 채움
        }
        login(s.username, Api.PASSWORD, "10.20.30." + (int) (Math.random() * 200))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("[SEC-05] 같은 IP 의 가입이 시간당 한도를 넘으면 429")
    void signUpIsRateLimitedPerIp() throws Exception {
        String ip = "10.30.40." + (int) (Math.random() * 200);
        while (rateLimiter.tryAcquire("signup:" + ip, 1000, Duration.ofHours(1))) {
            // 테스트 설정의 가입 한도(시간당 1000회)를 채움
        }
        api().perform(MockMvcRequestBuilders.post("/api/auth/signup").with(csrf())
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", "ratelim" + UUID.randomUUID().toString().substring(0, 6),
                                "password", "abcd1234", "passwordConfirm", "abcd1234", "name", "가입제한"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("[SEC-01] 로그인에 성공하면 계정 단위 시도 기록이 초기화된다")
    void successfulLoginResetsAccountLimit() throws Exception {
        Api.Session s = api().signUp("accreset");
        String key = "login-account:" + s.username.toLowerCase();
        for (int i = 0; i < 999; i++) {
            rateLimiter.tryAcquire(key, 1000, Duration.ofMinutes(10));
        }
        login(s.username, Api.PASSWORD, "10.20.31.1").andExpect(status().isOk());   // 1000번째 시도 → 성공 → 초기화
        login(s.username, Api.PASSWORD, "10.20.31.1").andExpect(status().isOk());   // 초기화되지 않았다면 429
    }

    @Test
    @DisplayName("[S-03] 악센트 등으로 바꾼 아이디로는 로그인할 수 없다 — DB 콜레이션이 악센트를 무시해 계정 단위 시도 제한을 우회했음")
    void accentVariantCannotLogIn() throws Exception {
        Api.Session s = api().signUp("accento");
        login(s.username.replaceFirst("e", "é"), Api.PASSWORD, "10.20.32.1").andExpect(status().isUnauthorized());
        login(s.username.replaceFirst("o", "ó"), Api.PASSWORD, "10.20.32.1").andExpect(status().isUnauthorized());
        login(s.username.toUpperCase(), Api.PASSWORD, "10.20.32.1").andExpect(status().isOk());   // 대소문자 무시는 그대로
    }

    @Test
    @DisplayName("[S-02] 비정상적으로 긴 아이디·비밀번호는 요청 제한 기록을 남기기 전에 400, 72바이트를 넘는 비밀번호는 401")
    void overlongCredentialsAreRejectedEarly() throws Exception {
        login("a".repeat(100_000), "x", "10.20.33.1").andExpect(status().isBadRequest());
        login("nobody", "p".repeat(100_000), "10.20.33.1").andExpect(status().isBadRequest());
        login("nobody", "p".repeat(100), "10.20.33.1").andExpect(status().isUnauthorized());
        login("bad name!", "x", "10.20.33.1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password, String ip) {
        return api().perform(MockMvcRequestBuilders.post("/api/auth/login").with(csrf())
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(api().toJson(Map.of("username", username, "password", password))));
    }

    @Test
    @DisplayName("[BUG-01] 글자 수는 72 이하지만 UTF-8 로 72바이트를 넘는 비밀번호(한글 32자)는 500 이 아니라 400 과 필드 메시지")
    void passwordOver72BytesIsRejected() throws Exception {
        String password = "a1" + "가".repeat(30);   // 32자, UTF-8 92바이트 — BCrypt 는 72바이트까지만 처리
        api().perform(MockMvcRequestBuilders.post("/api/auth/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", "longpw" + UUID.randomUUID().toString().substring(0, 6),
                                "password", password, "passwordConfirm", password, "name", "긴비번"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.password").value(containsString("72바이트")));
    }

    @Test
    @DisplayName("가입 검증: 아이디 형식·비밀번호 규칙 위반은 400과 필드 메시지")
    void signUpValidation() throws Exception {
        api().perform(MockMvcRequestBuilders.post("/api/auth/signup").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(api().toJson(Map.of("username", "a b", "password", "short",
                                "passwordConfirm", "short", "name", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors.username").exists());
    }
}
