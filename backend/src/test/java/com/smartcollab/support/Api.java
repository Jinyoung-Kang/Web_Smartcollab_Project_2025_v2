package com.smartcollab.support;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * 테스트용 HTTP 클라이언트. 실제 브라우저처럼 인증 쿠키(SC_AUTH)와 CSRF 토큰을 붙여 요청합니다.
 */
public class Api {

    public static final String PASSWORD = "passw0rd!";

    private final MockMvc mvc;
    private final ObjectMapper json;

    public Api(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public Session signUp(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String username = (prefix.length() > 11 ? prefix.substring(0, 11) : prefix) + "_" + suffix;
        MvcResult result = perform(MockMvcRequestBuilders.post("/api/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD,
                        "passwordConfirm", PASSWORD, "name", prefix + "님")))).andReturn();
        String body = content(result);
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("signup failed: " + body);
        }
        return new Session(username, ((Number) JsonPath.read(body, "$.id")).longValue(),
                ((Number) JsonPath.read(body, "$.rootFolderId")).longValue(), result.getResponse().getCookie("SC_AUTH"));
    }

    public ResultActions perform(org.springframework.test.web.servlet.RequestBuilder builder) {
        try {
            return mvc.perform(builder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String toJson(Object body) {
        return json.writeValueAsString(body);
    }

    public class Session {
        public final String username;
        public final long userId;
        public final long rootFolderId;
        public final Cookie cookie;

        Session(String username, long userId, long rootFolderId, Cookie cookie) {
            this.username = username;
            this.userId = userId;
            this.rootFolderId = rootFolderId;
            this.cookie = cookie;
        }

        public ResultActions send(AbstractMockHttpServletRequestBuilder<?> builder) {
            return perform(builder.cookie(cookie).with(csrf()));
        }

        public ResultActions get(String url, Object... vars) {
            return send(MockMvcRequestBuilders.get(url, vars));
        }

        public ResultActions delete(String url, Object... vars) {
            return send(MockMvcRequestBuilders.delete(url, vars));
        }

        public ResultActions post(String url, Object... vars) {
            return send(MockMvcRequestBuilders.post(url, vars));
        }

        public ResultActions postJson(String url, Object body, Object... vars) {
            return send(MockMvcRequestBuilders.post(url, vars).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
        }

        public ResultActions putJson(String url, Object body, Object... vars) {
            return send(MockMvcRequestBuilders.put(url, vars).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
        }

        public ResultActions patchJson(String url, Object body, Object... vars) {
            return send(MockMvcRequestBuilders.patch(url, vars).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
        }

        public ResultActions deleteJson(String url, Object body, Object... vars) {
            return send(MockMvcRequestBuilders.delete(url, vars).contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
        }

        public ResultActions upload(long folderId, String filename, byte[] bytes) {
            MockMultipartHttpServletRequestBuilder builder = MockMvcRequestBuilders.multipart("/api/files/upload");
            builder.file(new MockMultipartFile("file", filename, "application/octet-stream", bytes));
            builder.param("folderId", String.valueOf(folderId));
            return send(builder);
        }

        /** 업로드 후 파일 ID 반환 */
        public long uploadText(long folderId, String filename, String text) {
            return id(upload(folderId, filename, text.getBytes(StandardCharsets.UTF_8)));
        }

        public long createFolder(long parentId, String name) {
            return id(postJson("/api/folders", Map.of("parentId", parentId, "name", name)));
        }

        /** 팀 생성 → [teamId, rootFolderId] */
        public long[] createTeam(String name) {
            String body = body(postJson("/api/teams", Map.of("name", name)));
            return new long[]{((Number) JsonPath.read(body, "$.id")).longValue(),
                    ((Number) JsonPath.read(body, "$.rootFolderId")).longValue()};
        }
    }

    public static long id(ResultActions actions) {
        return ((Number) JsonPath.read(body(actions), "$.id")).longValue();
    }

    /** 2xx 가 아니면 실패로 처리하고 본문을 돌려줍니다. */
    public static String body(ResultActions actions) {
        MvcResult r = actions.andReturn();
        String content = content(r);
        if (r.getResponse().getStatus() >= 400) {
            throw new AssertionError("HTTP " + r.getResponse().getStatus() + ": " + content);
        }
        return content;
    }

    public static <T> T read(ResultActions actions, String path) {
        return JsonPath.read(body(actions), path);
    }

    private static String content(MvcResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
