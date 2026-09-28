package com.smartcollab.security;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1 감사에서 찾은 "권한 검사 누락(IDOR)" 경로가 모두 막혔는지 검증합니다.
 * 다른 사용자의 파일·폴더·팀 ID 를 알아도 존재 여부조차 알 수 없어야 합니다(404).
 */
class AccessControlTest extends IntegrationTest {

    Api.Session owner;
    Api.Session stranger;
    long fileId;
    long teamId;
    long teamRoot;
    long teamFileId;

    @BeforeEach
    void setUp() throws Exception {
        owner = api().signUp("owner");
        stranger = api().signUp("stranger");
        fileId = owner.uploadText(owner.rootFolderId, "비밀 문서.txt", "confidential");
        long[] team = owner.createTeam("비밀 팀");
        teamId = team[0];
        teamRoot = team[1];
        teamFileId = owner.uploadText(teamRoot, "팀 문서.md", "# team");
    }

    @Test
    @DisplayName("[v1: 인증 없이도 가능] 로그인하지 않은 사용자는 파일을 볼 수 없다 (/api/files/view 는 v1 에서 permitAll)")
    void anonymousCannotViewFile() throws Exception {
        api().perform(MockMvcRequestBuilders.get("/api/files/{id}", fileId)).andExpect(status().isUnauthorized());
        api().perform(MockMvcRequestBuilders.get("/api/files/{id}/view", fileId)).andExpect(status().isUnauthorized());
        api().perform(MockMvcRequestBuilders.get("/api/files/{id}/download", fileId)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[v1 IDOR] 다른 사람의 파일 정보·다운로드·미리보기·내용·버전·Office URL 은 404")
    void strangerCannotReadOthersFile() throws Exception {
        for (String url : List.of("/api/files/{id}", "/api/files/{id}/download", "/api/files/{id}/view", "/api/files/{id}/content",
                "/api/files/{id}/versions", "/api/files/{id}/office-preview-url", "/api/files/{id}/share-links")) {
            stranger.get(url, fileId).andExpect(status().isNotFound());
        }
        stranger.post("/api/files/{id}/summary", fileId).andExpect(status().isNotFound());
        stranger.post("/api/files/{id}/translation?target=EN", fileId).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 IDOR] 다른 사람의 파일 이름 변경·삭제·서명·공유 링크 생성은 404")
    void strangerCannotModifyOthersFile() throws Exception {
        stranger.patchJson("/api/files/{id}", Map.of("name", "hacked.txt"), fileId).andExpect(status().isNotFound());
        stranger.delete("/api/files/{id}", fileId).andExpect(status().isNotFound());
        stranger.post("/api/files/{id}/signatures", fileId).andExpect(status().isNotFound());
        stranger.postJson("/api/files/{id}/share-links", Map.of(), fileId).andExpect(status().isNotFound());
        stranger.putJson("/api/files/{id}/content", Map.of("content", "x", "baseVersionId", 1), fileId)
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 IDOR] 남의 파일을 내 폴더로 이동(탈취)하거나 복사할 수 없다")
    void strangerCannotMoveOrCopyOthersFile() throws Exception {
        Map<String, Object> req = Map.of("items", List.of(Map.of("type", "file", "id", fileId)),
                "targetFolderId", stranger.rootFolderId);
        stranger.postJson("/api/items/move", req).andExpect(status().isNotFound());
        stranger.postJson("/api/items/copy", req).andExpect(status().isNotFound());
        owner.get("/api/files/{id}/content", fileId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[v1 IDOR] 남의 폴더 내용·트리·검색에 접근할 수 없다")
    void strangerCannotBrowseOthersFolders() throws Exception {
        stranger.get("/api/folders/{id}", owner.rootFolderId).andExpect(status().isNotFound());
        stranger.get("/api/folders/tree?teamId={t}", teamId).andExpect(status().isNotFound());
        stranger.get("/api/files/search?q=문서&teamId={t}", teamId).andExpect(status().isNotFound());
        stranger.postJson("/api/folders", Map.of("parentId", owner.rootFolderId, "name", "침입")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 IDOR] 팀원이 아니면 팀 정보·멤버·채팅 기록·휴지통에 접근할 수 없다")
    void strangerCannotReadTeam() throws Exception {
        stranger.get("/api/teams/{t}", teamId).andExpect(status().isNotFound());
        stranger.get("/api/teams/{t}/messages", teamId).andExpect(status().isNotFound());
        stranger.get("/api/teams/{t}/presence", teamId).andExpect(status().isNotFound());
        stranger.get("/api/trash?teamId={t}", teamId).andExpect(status().isNotFound());
        stranger.get("/api/files/{id}/download", teamFileId).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 IDOR] 다른 팀의 팀장이라도 우리 팀 멤버의 권한을 바꾸거나 내보낼 수 없다")
    void leaderOfAnotherTeamCannotTouchMembers() throws Exception {
        long[] strangerTeam = stranger.createTeam("다른 팀");
        long ownerMemberId = ((Number) Api.read(owner.get("/api/teams/{t}", teamId), "$.members[0].memberId")).longValue();
        // stranger 는 자기 팀(strangerTeam)의 팀장이지만, owner 팀의 memberId 를 넣어 조작 시도
        stranger.putJson("/api/teams/{t}/members/{m}/permissions", Map.of("canEdit", false, "canDelete", false, "canInvite", false),
                strangerTeam[0], ownerMemberId).andExpect(status().isNotFound());
        stranger.delete("/api/teams/{t}/members/{m}", strangerTeam[0], ownerMemberId).andExpect(status().isNotFound());
        stranger.post("/api/teams/{t}/leader/{m}", strangerTeam[0], ownerMemberId).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 IDOR] 다른 파일의 버전 ID 로 복원해 그 내용을 훔쳐볼 수 없다")
    void cannotRestoreForeignVersion() throws Exception {
        long myFile = stranger.uploadText(stranger.rootFolderId, "내 파일.txt", "mine");
        long ownersVersion = ((Number) Api.read(owner.get("/api/files/{id}/versions", fileId), "$[0].versionId")).longValue();
        stranger.post("/api/files/{f}/versions/{v}/restore", myFile, ownersVersion).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 사람의 알림은 읽음 처리·삭제할 수 없다")
    void cannotTouchOthersNotifications() throws Exception {
        owner.postJson("/api/teams/{t}/invitations", Map.of("username", stranger.username), teamId)
                .andExpect(status().isCreated());
        long notificationId = ((Number) Api.read(stranger.get("/api/notifications"), "$.items[0].id")).longValue();
        owner.post("/api/notifications/{id}/read", notificationId).andExpect(status().isNotFound());
        owner.delete("/api/notifications/{id}", notificationId).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[v1 보안] 관리자 API(비밀번호 해시가 담긴 User 엔티티 노출)는 제거되었다")
    void adminEndpointsRemoved() throws Exception {
        owner.get("/api/admin/users").andExpect(status().isNotFound());
    }
}
