package com.smartcollab.auth;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-10] 화면은 처음 열릴 때 /api/auth/me 로 로그인 여부를 확인해, 로그인 전 화면(로그인·공유 받기)마다 401 이 브라우저 콘솔
 * 오류로 찍혔습니다(Lighthouse 모범 사례 96). 로그인 여부를 오류 없이 알려 주는 /api/auth/session 을 둡니다. /me 는 그대로 401.
 */
class AuthSessionTest extends IntegrationTest {

    @Test
    @DisplayName("[IMP-10] 로그인 전에는 200 과 authenticated=false")
    void anonymous() throws Exception {
        api().perform(MockMvcRequestBuilders.get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist());
    }

    @Test
    @DisplayName("[IMP-10] 로그인했으면 내 정보를 함께 돌려준다")
    void authenticated() throws Exception {
        Api.Session s = api().signUp("imp10");
        s.get("/api/auth/session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value(s.username))
                .andExpect(jsonPath("$.user.rootFolderId").value(s.rootFolderId));
    }

    @Test
    @DisplayName("[IMP-10] 만료·위조된 쿠키면 200 과 authenticated=false 이고, 쿠키를 지운다 (/me 는 그대로 401)")
    void invalidCookie() throws Exception {
        api().perform(MockMvcRequestBuilders.get("/api/auth/session").cookie(new Cookie("SC_AUTH", "not-a-jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(header().string("Set-Cookie", containsString("SC_AUTH=")));
        api().perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
