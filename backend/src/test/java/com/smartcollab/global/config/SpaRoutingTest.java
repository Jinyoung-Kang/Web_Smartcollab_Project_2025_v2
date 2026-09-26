package com.smartcollab.global.config;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 브라우저 주소창으로 직접 연 화면 경로(새로고침·딥링크)는 SPA 진입점(index.html)으로, API·정적 파일은 그대로 처리됩니다.
 */
class SpaRoutingTest extends IntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/search", "/drive", "/drive/12", "/teams/3/folders/4", "/teams/3/trash",
            "/files/9/edit", "/share/abcDEF_-123", "/login", "/unknown/client/route"})
    @DisplayName("[BUG-02] 화면 경로는 index.html 로 보낸다 (검색 화면 새로고침 시 404 였음)")
    void clientRoutesForwardToIndex(String path) throws Exception {
        mvc.perform(get(path).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/assets/missing-abc123.js", "/favicon.svg", "/swagger-ui.html"})
    @DisplayName("[BUG-02] 파일 경로(점이 있는 마지막 조각)는 index.html 로 보내지 않는다")
    void filesAreNotForwarded(String path) throws Exception {
        String forwarded = mvc.perform(get(path)).andReturn().getResponse().getForwardedUrl();
        assertThat(forwarded).isNotEqualTo("/index.html");
    }

    @Test
    @DisplayName("[BUG-02] 없는 API 는 index.html 이 아니라 API 오류로 응답한다")
    void unknownApiIsNotForwarded() throws Exception {
        mvc.perform(get("/api/no-such-endpoint").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getForwardedUrl()).isNull());
    }
}
