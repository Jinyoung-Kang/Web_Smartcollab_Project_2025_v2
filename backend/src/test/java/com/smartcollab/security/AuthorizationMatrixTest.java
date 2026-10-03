package com.smartcollab.security;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-09] 인가 행렬 — 사용자·팀 조합마다 남의 자원 ID(또는 ID 두 개를 섞은 요청)로 API 를 불러, 기대한 상태 코드와 "부수 효과 없음"을
 * 확인합니다. 출시 기준 QA 의 블랙박스 점검(qa/scripts/authz-matrix.mjs, 80요청·부수 효과 17건)을 CI 시험으로 옮겼습니다.
 * 권한 거부 분기 일부(폴더 삭제·파일 수정·서명·초대 권한 없음)가 CI 시험에서 빠져 있었습니다(JaCoCo).
 * <ul>
 *   <li>A: 개인 자원 주인, 팀 T1 팀장 / B: 외부인, 팀 T2 팀장 / C: T1 의 읽기 전용 멤버</li>
 *   <li>읽을 수 없는 대상은 404(존재 여부 비공개), 읽을 수 있지만 권한이 모자라면 403</li>
 * </ul>
 */
class AuthorizationMatrixTest extends IntegrationTest {

    private static final Set<Integer> NOT_FOUND = Set.of(404);
    private static final Set<Integer> FORBIDDEN = Set.of(403);
    private static final Set<Integer> REJECTED = Set.of(400, 403, 404, 409);

    private Api.Session a;
    private Api.Session b;
    private Api.Session c;
    private long aFolder;
    private long aFile;
    private long aOldVersion;
    private long aCurrentVersion;
    private long aLink;
    private long aTrashedFile;
    private long aTrashedFolder;
    private long aInvitation;      // B 가 A 를 T2 에 초대한 것 (A 에게 온 초대)
    private long aNotification;
    private long t1;
    private long t1Root;
    private long t1File;
    private long t1Folder;
    private long t1Version;
    private long cMember;
    private long bFile;
    private long t2;

    private record Call(Api.Session who, HttpMethod method, String path, Object body, Set<Integer> expected, String note) {
    }

    @BeforeEach
    void setUp() throws Exception {
        a = api().signUp("mxa");
        b = api().signUp("mxb");
        c = api().signUp("mxc");

        aFolder = a.createFolder(a.rootFolderId, "a-folder");
        aFile = a.uploadText(a.rootFolderId, "a.txt", "A 비밀 1판");
        aOldVersion = versionOf(a, aFile);
        a.putJson("/api/files/{id}/content", Map.of("content", "A 비밀 2판", "baseVersionId", aOldVersion), aFile).andExpect(status().isOk());
        aCurrentVersion = versionOf(a, aFile);
        aLink = Api.id(a.postJson("/api/files/{id}/share-links", Map.of("password", "pass1234"), aFile));
        aTrashedFile = a.uploadText(a.rootFolderId, "trash.txt", "t");
        a.delete("/api/files/{id}", aTrashedFile).andExpect(status().isNoContent());
        aTrashedFolder = a.createFolder(a.rootFolderId, "trash-folder");
        a.delete("/api/folders/{id}", aTrashedFolder).andExpect(status().isNoContent());

        long[] team1 = a.createTeam("mx-t1");
        t1 = team1[0];
        t1Root = team1[1];
        cMember = join(a, c, t1);
        a.putJson("/api/teams/{t}/members/{m}/permissions", Map.of("canEdit", false, "canDelete", false, "canInvite", false), t1, cMember)
                .andExpect(status().isNoContent());
        t1File = a.uploadText(t1Root, "team.txt", "팀 비밀");
        t1Folder = a.createFolder(t1Root, "t1-folder");
        t1Version = versionOf(a, t1File);

        bFile = b.uploadText(b.rootFolderId, "b.txt", "B 1판");
        long bBase = versionOf(b, bFile);
        b.putJson("/api/files/{id}/content", Map.of("content", "B 2판", "baseVersionId", bBase), bFile).andExpect(status().isOk());
        t2 = b.createTeam("mx-t2")[0];

        b.postJson("/api/teams/{t}/invitations", Map.of("username", a.username), t2).andExpect(status().isCreated());
        aNotification = ((Number) Api.read(a.get("/api/notifications"), "$.items[0].id")).longValue();
        aInvitation = ((Number) Api.read(a.get("/api/notifications"), "$.items[0].invitationId")).longValue();
    }

    private static long versionOf(Api.Session s, long fileId) {
        return ((Number) Api.read(s.get("/api/files/{id}/content", fileId), "$.versionId")).longValue();
    }

    private long join(Api.Session leader, Api.Session member, long teamId) throws Exception {
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), teamId).andExpect(status().isCreated());
        long inv = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", inv).andExpect(status().isNoContent());
        List<Map<String, Object>> members = Api.read(leader.get("/api/teams/{t}", teamId), "$.members");
        return members.stream().filter(m -> member.username.equals(m.get("username")))
                .map(m -> ((Number) m.get("memberId")).longValue()).findFirst().orElseThrow();
    }

    private void verify(List<Call> calls) {
        SoftAssertions soft = new SoftAssertions();
        for (Call call : calls) {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(call.method(), call.path());
            if (call.body() != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(api().toJson(call.body()));
            }
            int actual = call.who().send(request).andReturn().getResponse().getStatus();
            soft.assertThat(actual)
                    .as("%s %s %s (%s)", call.who().username, call.method(), call.path(), call.note())
                    .isIn(call.expected());
        }
        soft.assertAll();
    }

    private static Call call(Api.Session who, HttpMethod method, String path, Object body, Set<Integer> expected, String note) {
        return new Call(who, method, path, body, expected, note);
    }

    private static Call call(Api.Session who, HttpMethod method, String path, Set<Integer> expected) {
        return new Call(who, method, path, null, expected, "");
    }

    @Test
    @DisplayName("[IMP-09] 외부인은 남의 개인 자원·팀을 읽지도 바꾸지도 못한다 (404, 부수 효과 없음)")
    void outsiderCannotTouchOthers() throws Exception {
        verify(List.of(
                call(b, HttpMethod.GET, "/api/folders/" + a.rootFolderId, NOT_FOUND),
                call(b, HttpMethod.GET, "/api/folders/" + aFolder, NOT_FOUND),
                call(b, HttpMethod.POST, "/api/folders", Map.of("parentId", aFolder, "name", "x"), NOT_FOUND, "남의 폴더에 만들기"),
                call(b, HttpMethod.PATCH, "/api/folders/" + aFolder, Map.of("name", "hacked"), NOT_FOUND, ""),
                call(b, HttpMethod.DELETE, "/api/folders/" + aFolder, NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile, NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile + "/download", NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile + "/view", NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile + "/content", NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile + "/versions", NOT_FOUND),
                call(b, HttpMethod.GET, "/api/files/" + aFile + "/share-links", NOT_FOUND),
                call(b, HttpMethod.PATCH, "/api/files/" + aFile, Map.of("name", "hacked.txt"), NOT_FOUND, ""),
                call(b, HttpMethod.PUT, "/api/files/" + aFile + "/content", Map.of("content", "hacked", "baseVersionId", aCurrentVersion), NOT_FOUND, ""),
                call(b, HttpMethod.POST, "/api/files/" + aFile + "/versions/" + aOldVersion + "/restore", NOT_FOUND),
                call(b, HttpMethod.POST, "/api/files/" + aFile + "/signatures", NOT_FOUND),
                call(b, HttpMethod.POST, "/api/files/" + aFile + "/share-links", Map.of(), NOT_FOUND, ""),
                call(b, HttpMethod.POST, "/api/files/" + aFile + "/summary", NOT_FOUND),
                call(b, HttpMethod.POST, "/api/items/move", items("file", aFile, b.rootFolderId), NOT_FOUND, "남의 파일을 내 폴더로"),
                call(b, HttpMethod.POST, "/api/items/copy", items("folder", aFolder, b.rootFolderId), NOT_FOUND, "남의 폴더 복사"),
                call(b, HttpMethod.POST, "/api/items/delete", Map.of("items", List.of(Map.of("type", "file", "id", aFile))), NOT_FOUND, ""),
                call(b, HttpMethod.POST, "/api/items/move", items("file", bFile, aFolder), NOT_FOUND, "내 파일을 남의 폴더로"),
                call(b, HttpMethod.POST, "/api/trash/" + aTrashedFile + "/restore", NOT_FOUND),
                call(b, HttpMethod.DELETE, "/api/trash/" + aTrashedFile, NOT_FOUND),
                call(b, HttpMethod.POST, "/api/trash/folders/" + aTrashedFolder + "/restore", NOT_FOUND),
                call(b, HttpMethod.DELETE, "/api/trash/folders/" + aTrashedFolder, NOT_FOUND),
                call(b, HttpMethod.DELETE, "/api/share-links/" + aLink, NOT_FOUND),
                call(b, HttpMethod.POST, "/api/notifications/" + aNotification + "/read", NOT_FOUND),
                call(b, HttpMethod.DELETE, "/api/notifications/" + aNotification, NOT_FOUND),
                call(b, HttpMethod.POST, "/api/invitations/" + aInvitation + "/accept", null, NOT_FOUND, "보낸 사람이 자기 초대를 수락"),
                call(c, HttpMethod.POST, "/api/invitations/" + aInvitation + "/reject", null, NOT_FOUND, "남에게 온 초대를 거절"),
                call(b, HttpMethod.GET, "/api/teams/" + t1, NOT_FOUND),
                call(b, HttpMethod.GET, "/api/teams/" + t1 + "/messages", NOT_FOUND),
                call(b, HttpMethod.POST, "/api/teams/" + t1 + "/messages", Map.of("content", "hi"), NOT_FOUND, ""),
                call(b, HttpMethod.POST, "/api/teams/" + t1 + "/invitations", Map.of("username", b.username), NOT_FOUND, "스스로 초대"),
                call(b, HttpMethod.PUT, "/api/teams/" + t1 + "/members/" + cMember + "/permissions", allPermissions(), NOT_FOUND, ""),
                call(b, HttpMethod.DELETE, "/api/teams/" + t1 + "/members/" + cMember, NOT_FOUND),
                call(b, HttpMethod.POST, "/api/teams/" + t1 + "/leader/" + cMember, NOT_FOUND),
                call(b, HttpMethod.DELETE, "/api/teams/" + t1, NOT_FOUND),
                call(b, HttpMethod.GET, "/api/folders/tree?teamId=" + t1, Set.of(403, 404)),
                call(b, HttpMethod.GET, "/api/files/search?q=team&teamId=" + t1, Set.of(403, 404)),
                call(b, HttpMethod.DELETE, "/api/trash?teamId=" + t1, Set.of(403, 404)),
                call(b, HttpMethod.GET, "/api/files/" + t1File + "/download", NOT_FOUND)));
        assertNothingChanged();
    }

    @Test
    @DisplayName("[IMP-09] 경로의 앞 ID 는 내 것, 뒤 ID·본문은 남의 것인 요청은 거절한다 (부수 효과 없음)")
    void mixedIdsAreRejected() throws Exception {
        verify(List.of(
                call(b, HttpMethod.PUT, "/api/teams/" + t2 + "/members/" + cMember + "/permissions", allPermissions(), REJECTED, "T2 경로 + T1 멤버"),
                call(b, HttpMethod.DELETE, "/api/teams/" + t2 + "/members/" + cMember, null, REJECTED, "T2 경로 + T1 멤버"),
                call(b, HttpMethod.POST, "/api/teams/" + t2 + "/leader/" + cMember, null, REJECTED, "T2 경로 + T1 멤버"),
                call(b, HttpMethod.POST, "/api/files/" + bFile + "/versions/" + aOldVersion + "/restore", null, REJECTED, "B 파일 + A 파일의 버전"),
                call(b, HttpMethod.PUT, "/api/files/" + bFile + "/content", Map.of("content", "x", "baseVersionId", aOldVersion), REJECTED, "B 파일 + A 버전 기준"),
                call(b, HttpMethod.POST, "/api/teams/" + t2 + "/messages", Map.of("fileId", aFile), REJECTED, "T2 채팅에 A 개인 파일"),
                call(b, HttpMethod.POST, "/api/teams/" + t2 + "/messages", Map.of("fileId", t1File), REJECTED, "T2 채팅에 T1 파일"),
                call(b, HttpMethod.POST, "/api/teams/" + t2 + "/messages", Map.of("fileId", bFile), REJECTED, "T2 채팅에 B 개인 파일")));
        assertNothingChanged();
        assertThat(Api.body(b.get("/api/files/{id}/content", bFile))).contains("B 2판");
    }

    @Test
    @DisplayName("[IMP-09] 읽기 전용 멤버는 팀 자원을 바꾸거나 관리하지 못한다 (403, 부수 효과 없음)")
    void readOnlyMemberCannotWrite() throws Exception {
        verify(List.of(
                call(c, HttpMethod.POST, "/api/folders", Map.of("parentId", t1Root, "name", "c"), FORBIDDEN, ""),
                call(c, HttpMethod.PATCH, "/api/folders/" + t1Folder, Map.of("name", "c"), FORBIDDEN, ""),
                call(c, HttpMethod.DELETE, "/api/folders/" + t1Folder, FORBIDDEN),
                call(c, HttpMethod.PATCH, "/api/files/" + t1File, Map.of("name", "c.txt"), FORBIDDEN, "파일 수정 권한"),
                call(c, HttpMethod.DELETE, "/api/files/" + t1File, FORBIDDEN),
                call(c, HttpMethod.POST, "/api/items/delete", Map.of("items", List.of(Map.of("type", "file", "id", t1File))), FORBIDDEN, "삭제 권한"),
                call(c, HttpMethod.POST, "/api/items/move", items("file", t1File, c.rootFolderId), FORBIDDEN, "팀 파일을 내 드라이브로"),
                call(c, HttpMethod.PUT, "/api/files/" + t1File + "/content", Map.of("content", "c", "baseVersionId", t1Version), FORBIDDEN, ""),
                call(c, HttpMethod.POST, "/api/files/" + t1File + "/versions/" + t1Version + "/restore", null, Set.of(403, 409), ""),
                call(c, HttpMethod.POST, "/api/files/" + t1File + "/signatures", null, FORBIDDEN, "서명 권한"),
                call(c, HttpMethod.POST, "/api/teams/" + t1 + "/invitations", Map.of("username", b.username), FORBIDDEN, "초대 권한"),
                call(c, HttpMethod.PUT, "/api/teams/" + t1 + "/members/" + cMember + "/permissions", allPermissions(), FORBIDDEN, "자기 권한 올리기"),
                call(c, HttpMethod.DELETE, "/api/teams/" + t1 + "/messages", FORBIDDEN),
                call(c, HttpMethod.DELETE, "/api/teams/" + t1, FORBIDDEN),
                call(c, HttpMethod.DELETE, "/api/trash?teamId=" + t1, FORBIDDEN),
                call(c, HttpMethod.POST, "/api/teams/" + t1 + "/leader/" + cMember, FORBIDDEN),
                call(c, HttpMethod.POST, "/api/teams/" + t1 + "/messages", Map.of("fileId", t1File), Set.of(200, 201), "읽기 전용도 팀 파일 공유는 가능(기준)")));
        assertNothingChanged();
    }

    private static Map<String, Object> items(String type, long id, long target) {
        return Map.of("items", List.of(Map.of("type", type, "id", id)), "targetFolderId", target);
    }

    private static Map<String, Object> allPermissions() {
        return Map.of("canEdit", true, "canDelete", true, "canInvite", true);
    }

    /** 주인이 다시 조회해도 그대로여야 합니다 */
    private void assertNothingChanged() throws Exception {
        assertThat(Api.body(a.get("/api/files/{id}/content", aFile))).contains("A 비밀 2판");
        assertThat((String) Api.read(a.get("/api/files/{id}", aFile), "$.name")).isEqualTo("a.txt");
        assertThat((String) Api.read(a.get("/api/folders/{id}", aFolder), "$.folder.name")).isEqualTo("a-folder");
        assertThat((List<?>) Api.read(a.get("/api/folders/{id}", aFolder), "$.items")).isEmpty();
        assertThat((Boolean) Api.read(a.get("/api/files/{id}/share-links", aFile), "$[0].active")).isTrue();
        List<Map<String, Object>> trash = Api.read(a.get("/api/trash"), "$");
        assertThat(trash).anySatisfy(t -> assertThat(t).containsEntry("type", "file").containsEntry("id", (int) aTrashedFile));
        assertThat(trash).anySatisfy(t -> assertThat(t).containsEntry("type", "folder").containsEntry("id", (int) aTrashedFolder));
        List<Map<String, Object>> members = Api.read(a.get("/api/teams/{t}", t1), "$.members");
        assertThat(members).hasSize(2);
        assertThat(members).filteredOn(m -> Boolean.TRUE.equals(m.get("leader"))).extracting(m -> m.get("username")).containsExactly(a.username);
        assertThat(members).filteredOn(m -> ((Number) m.get("memberId")).longValue() == cMember)
                .allSatisfy(m -> assertThat(m).containsEntry("canEdit", false).containsEntry("canDelete", false).containsEntry("canInvite", false));
        List<Boolean> read = Api.read(a.get("/api/notifications"), "$.items[?(@.id == " + aNotification + ")].read");
        assertThat(read).containsExactly(false);
        assertThat(Api.body(a.get("/api/teams/{t}/messages", t1))).doesNotContain(b.username);
        assertThat(Api.body(b.get("/api/teams/{t}/messages", t2))).doesNotContain("a.txt").doesNotContain("team.txt");
    }
}
