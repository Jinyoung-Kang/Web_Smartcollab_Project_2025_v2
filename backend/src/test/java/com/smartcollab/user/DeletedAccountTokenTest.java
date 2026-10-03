package com.smartcollab.user;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [QA-07] 탈퇴한 계정의 토큰(JWT)이 만료(최대 8시간)까지 일부 API 에서 통했습니다. /api/auth/me 만 401 이고
 * /api/teams·/api/notifications 등은 200(빈 데이터), 알림 읽음·삭제는 204 여서, 다른 기기에 열려 있던 화면이 로그인 화면으로
 * 돌아가지 않았습니다. 탈퇴가 커밋되면 그 사용자의 토큰은 쿠키·Bearer 모두 401 이어야 합니다.
 */
class DeletedAccountTokenTest extends IntegrationTest {

    @Test
    @DisplayName("[QA-07] 탈퇴한 계정의 토큰은 모든 API 에서 401 이다 (쿠키·Bearer)")
    void deletedAccountTokenIsRejectedEverywhere() throws Exception {
        Api.Session s = api().signUp("qa07");
        Api.Session otherDevice = api().login(s.username, Api.PASSWORD);   // 다른 기기의 세션

        s.postJson("/api/users/me/delete", Map.of("password", Api.PASSWORD)).andExpect(status().isNoContent());

        for (String path : new String[]{"/api/auth/me", "/api/teams", "/api/notifications", "/api/files/usage", "/api/trash", "/api/folders/tree"}) {
            otherDevice.get(path).andExpect(status().isUnauthorized());
        }
        otherDevice.post("/api/notifications/read-all").andExpect(status().isUnauthorized());
        otherDevice.send(MockMvcRequestBuilders.delete("/api/notifications")).andExpect(status().isUnauthorized());
        api().perform(MockMvcRequestBuilders.get("/api/teams")
                        .header("Authorization", "Bearer " + otherDevice.cookie.getValue()))
                .andExpect(status().isUnauthorized());
        api().perform(MockMvcRequestBuilders.post("/api/folders").with(csrf())
                        .header("Authorization", "Bearer " + otherDevice.cookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + s.rootFolderId + ",\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[QA-07] 탈퇴가 거절되면(비밀번호 오류) 토큰은 그대로 쓸 수 있다")
    void failedDeletionKeepsToken() throws Exception {
        Api.Session s = api().signUp("qa07k");

        s.postJson("/api/users/me/delete", Map.of("password", "wrong-password-1")).andExpect(status().is4xxClientError());

        s.get("/api/teams").andExpect(status().isOk());
    }
}
