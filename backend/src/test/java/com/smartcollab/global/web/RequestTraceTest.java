package com.smartcollab.global.web;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * [ARC-02·SEC-11] 요청 추적과 보안 이벤트 기록.
 * 요청마다 추적 ID 를 응답 헤더·오류 본문·로그에 남기고, API 접근과 로그인 실패를 기록합니다.
 * 로그에는 비밀번호·아이디·쿼리 문자열(공유 다운로드 허가 등)을 남기지 않습니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestTraceTest extends IntegrationTest {

    @Test
    @DisplayName("요청마다 추적 ID 를 응답 헤더와 오류 본문에 담고, 접근 기록에 같은 ID 를 남긴다")
    void tagsResponsesAndLogsWithRequestId(CapturedOutput output) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/auth/me")).andReturn().getResponse();

        String id = res.getHeader(RequestTraceFilter.HEADER);
        assertThat(id).matches("[0-9a-f]{32}");
        assertThat(res.getContentAsString()).contains("\"requestId\":\"" + id + "\"");
        assertThat(output).containsPattern("\\[" + id + "] .*GET /api/auth/me 401 \\d+ms");
    }

    @Test
    @DisplayName("프록시가 준 추적 ID 는 이어 쓰고, 형식이 이상하면(로그 위조 방지) 새로 만든다")
    void reusesOnlyWellFormedIncomingIds() throws Exception {
        String given = mvc.perform(get("/api/auth/me").header(RequestTraceFilter.HEADER, "gw-1234abcd"))
                .andReturn().getResponse().getHeader(RequestTraceFilter.HEADER);
        String forged = mvc.perform(get("/api/auth/me").header(RequestTraceFilter.HEADER, "x\n2026-01-01 INFO fake"))
                .andReturn().getResponse().getHeader(RequestTraceFilter.HEADER);

        assertThat(given).isEqualTo("gw-1234abcd");
        assertThat(forged).matches("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("[S-07] 공유 링크 토큰은 그 자체로 접근 권한이라 접근 기록의 경로에서 가린다")
    void masksShareTokensInAccessLog(CapturedOutput output) throws Exception {
        String token = "Zk3v9QwLtYbN0pRs7uXa2cDe5fGh8iJk";
        mvc.perform(get("/api/public/shares/{t}", token));
        mvc.perform(get("/api/public/shares/{t}/download", token));
        assertThat(output).doesNotContain(token);
        assertThat(output).contains("GET /api/public/shares/*** 404").contains("GET /api/public/shares/***/download");
    }

    @Test
    @DisplayName("접근 기록에는 쿼리 문자열을 남기지 않는다 (공유 다운로드 허가 등 비밀값이 들어갈 수 있음)")
    void doesNotLogQueryStrings(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/public/shares/no-such-token/download").param("grant", "SECRET-GRANT-VALUE"));

        assertThat(output).contains("GET /api/public/shares/***/download 404");
        assertThat(output).doesNotContain("SECRET-GRANT-VALUE");
    }

    @Test
    @DisplayName("로그인 실패·성공을 보안 이벤트로 남기되, 입력한 아이디·비밀번호는 남기지 않는다")
    void logsLoginEventsWithoutCredentials(CapturedOutput output) throws Exception {
        Api.Session s = api().signUp("trace");
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", s.username, "password", "Wrong-Pass-4821"))));

        assertThat(output).containsPattern("security-event login-failed ip=\\S+");
        assertThat(output).doesNotContain("Wrong-Pass-4821");
        assertThat(output).doesNotContain("login-failed ip=" + s.username);

        api().login(s.username, Api.PASSWORD);
        assertThat(output).containsPattern("security-event login-succeeded user=" + s.userId + " ip=\\S+");
    }
}
