package com.smartcollab.user;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountDeletionTest extends IntegrationTest {

    @Test
    @DisplayName("[v1 버그] 개인 휴지통·팀 파일 편집 기록·공유 링크·채팅이 있어도 탈퇴된다 (v1: FK 오류)")
    void deleteAccountWithHistory() throws Exception {
        Api.Session leader = api().signUp("keeper");
        Api.Session leaving = api().signUp("leaving");
        long[] team = leader.createTeam("남는 팀");
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", leaving.username), team[0]);
        long inv = ((Number) Api.read(leaving.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        leaving.post("/api/invitations/{id}/accept", inv);

        // 개인 스토리지: 휴지통 파일 + 공유 링크
        long personal = leaving.uploadText(leaving.rootFolderId, "개인.txt", "p");
        long trashed = leaving.uploadText(leaving.rootFolderId, "버린.txt", "t");
        leaving.delete("/api/files/{id}", trashed);
        leaving.postJson("/api/files/{id}/share-links", Map.of(), personal);
        // 팀 스토리지: 팀장 파일을 편집(버전 작성자) + 자기 파일 업로드 + 채팅
        long leadersDoc = leader.uploadText(team[1], "공동 문서.md", "v1");
        long base = ((Number) Api.read(leaving.get("/api/files/{id}/content", leadersDoc), "$.versionId")).longValue();
        leaving.putJson("/api/files/{id}/content", Map.of("content", "v2 by leaving", "baseVersionId", base), leadersDoc);
        long teamFile = leaving.uploadText(team[1], "팀에 남길 파일.txt", "keep");
        leaving.postJson("/api/teams/{t}/messages", Map.of("content", "안녕히 계세요"), team[0]);

        leaving.postJson("/api/users/me/delete", Map.of("password", "wrong")).andExpect(status().isForbidden());
        leaving.postJson("/api/users/me/delete", Map.of("password", Api.PASSWORD)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from users where user_id = ?", Integer.class, leaving.userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files where file_id in (?, ?)", Integer.class, personal, trashed)).isZero();
        // 팀 자료는 남고 작성자가 '탈퇴한 사용자'로 바뀜
        leader.get("/api/folders/{id}", team[1])
                .andExpect(jsonPath("$.items[?(@.id == " + teamFile + ")].ownerName").value("탈퇴한 사용자"));
        leader.get("/api/files/{id}/versions", leadersDoc).andExpect(jsonPath("$[0].editorName").value("탈퇴한 사용자"));
        leader.get("/api/teams/{t}/messages", team[0]).andExpect(jsonPath("$.messages[0].sender.name").value("탈퇴한 사용자"));
        leader.get("/api/teams/{t}", team[0]).andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    @DisplayName("팀장인 팀이 있으면 탈퇴 전에 위임·삭제를 안내한다 (409)")
    void leaderMustDelegateFirst() throws Exception {
        Api.Session leader = api().signUp("stuck");
        leader.createTeam("내가 팀장");
        leader.postJson("/api/users/me/delete", Map.of("password", Api.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("내가 팀장")));
    }
}
