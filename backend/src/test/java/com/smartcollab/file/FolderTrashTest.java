package com.smartcollab.file;

import com.jayway.jsonpath.JsonPath;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [UX-06] 폴더 휴지통. 이전에는 폴더를 지우면 안의 파일까지 즉시 영구 삭제되어, 실수하면 되돌릴 수 없었습니다
 * (팀에서는 삭제 권한이 있는 멤버가 다른 사람의 자료가 든 팀 폴더도 지울 수 있음). 이제 파일처럼 30일 동안 휴지통에 둡니다.
 */
class FolderTrashTest extends IntegrationTest {

    @Autowired
    TrashService trashService;

    /** 내 드라이브 / A(g.txt) / B(f.txt) */
    private record Tree(Api.Session s, long a, long b, long f, long g) {
    }

    private Tree tree(String prefix) throws Exception {
        Api.Session s = api().signUp(prefix);
        long a = s.createFolder(s.rootFolderId, "보관함");
        long b = s.createFolder(a, "하위");
        long f = s.uploadText(b, "깊은 문서.txt", "deep");
        long g = s.uploadText(a, "얕은 문서.txt", "shallow");
        return new Tree(s, a, b, f, g);
    }

    @Test
    @DisplayName("폴더를 지우면 휴지통으로 가고, 안의 폴더·파일은 드라이브·트리·검색·직접 접근·공유 링크에서 모두 사라진다")
    void trashedFolderTreeIsHiddenEverywhere() throws Exception {
        Tree t = tree("ftrash");
        String token = JsonPath.read(Api.body(t.s().postJson("/api/files/{id}/share-links", Map.of(), t.g())), "$.token");

        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());

        t.s().get("/api/folders/{id}", t.s().rootFolderId).andExpect(jsonPath("$.items", hasSize(0)));
        t.s().get("/api/folders/{id}", t.a()).andExpect(status().isNotFound());
        t.s().get("/api/folders/{id}", t.b()).andExpect(status().isNotFound());
        t.s().get("/api/files/{id}/download", t.f()).andExpect(status().isNotFound());
        t.s().get("/api/files/{id}/content", t.g()).andExpect(status().isNotFound());
        t.s().get("/api/files/search?q={q}", "문서").andExpect(jsonPath("$", hasSize(0)));
        t.s().get("/api/folders/tree").andExpect(jsonPath("$.roots[0].children", hasSize(0)));
        t.s().get("/api/files/usage").andExpect(jsonPath("$.fileCount").value(0));
        api().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/shares/{token}", token))
                .andExpect(status().isGone());

        t.s().get("/api/trash")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("folder"))
                .andExpect(jsonPath("$[0].id").value(t.a()))
                .andExpect(jsonPath("$[0].fileCount").value(2))
                .andExpect(jsonPath("$[0].deletedByName").exists());
    }

    @Test
    @DisplayName("복원하면 하위 폴더·파일까지 원래 자리로 돌아온다")
    void restoresWholeTree() throws Exception {
        Tree t = tree("frestore");
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());

        t.s().post("/api/trash/folders/{id}/restore", t.a())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folderId").value(t.s().rootFolderId))
                .andExpect(jsonPath("$.relocated").value(false));

        t.s().get("/api/folders/{id}", t.s().rootFolderId).andExpect(jsonPath("$.items", hasSize(1)));
        t.s().get("/api/folders/{id}", t.b()).andExpect(status().isOk());
        t.s().get("/api/files/{id}/download", t.f()).andExpect(status().isOk());
        t.s().get("/api/trash").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("[v1 버그 유지] 휴지통에서 영구 삭제하면 휴지통·서명 파일이 든 하위까지 지워지고, 저장 공간도 돌아온다")
    void purgesTreePermanently() throws Exception {
        Tree t = tree("fpurge");
        t.s().delete("/api/files/{id}", t.f()).andExpect(status().isNoContent());          // 폴더 안의 개별 휴지통 파일
        t.s().post("/api/files/{id}/signatures", t.g()).andExpect(status().isCreated());    // 서명된 파일
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());

        t.s().delete("/api/trash/folders/{id}", t.a()).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from folders where folder_id in (?, ?)", Integer.class, t.a(), t.b())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files where file_id in (?, ?)", Integer.class, t.f(), t.g())).isZero();
        t.s().get("/api/files/usage").andExpect(jsonPath("$.storedBytes").value(0));
    }

    @Test
    @DisplayName("상위 폴더가 휴지통에 있으면, 따로 지웠던 하위 폴더는 최상위 폴더로 복원한다")
    void restoresIntoRootWhenParentIsInTrash() throws Exception {
        Tree t = tree("frelocate");
        t.s().delete("/api/folders/{id}", t.b()).andExpect(status().isNoContent());
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());
        t.s().get("/api/trash").andExpect(jsonPath("$", hasSize(2)));

        t.s().post("/api/trash/folders/{id}/restore", t.b())
                .andExpect(jsonPath("$.folderId").value(t.s().rootFolderId))
                .andExpect(jsonPath("$.relocated").value(true));

        t.s().get("/api/folders/{id}", t.b()).andExpect(status().isOk());
        t.s().get("/api/folders/{id}", t.a()).andExpect(status().isNotFound());
        t.s().get("/api/trash").andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(t.a()));
    }

    @Test
    @DisplayName("휴지통의 폴더는 이동·업로드·하위 폴더 만들기·다시 지우기를 할 수 없다 (404)")
    void trashedFolderCannotBeUsed() throws Exception {
        Tree t = tree("fblocked");
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());

        t.s().postJson("/api/items/move", Map.of("items", List.of(Map.of("type", "folder", "id", t.a())),
                "targetFolderId", t.s().rootFolderId)).andExpect(status().isNotFound());
        t.s().upload(t.b(), "new.txt", "x".getBytes()).andExpect(status().isNotFound());
        t.s().postJson("/api/folders", Map.of("parentId", t.b(), "name", "새 폴더")).andExpect(status().isNotFound());
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("폴더를 복사할 때 휴지통에 있는 하위 폴더는 복사하지 않는다")
    void copySkipsTrashedSubfolders() throws Exception {
        Tree t = tree("fcopy");
        t.s().delete("/api/folders/{id}", t.b()).andExpect(status().isNoContent());
        long dest = t.s().createFolder(t.s().rootFolderId, "사본");

        t.s().postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "folder", "id", t.a())), "targetFolderId", dest))
                .andExpect(jsonPath("$.copiedFiles").value(1));

        long copied = ((Number) Api.read(t.s().get("/api/folders/{id}", dest), "$.items[0].id")).longValue();
        t.s().get("/api/folders/{id}", copied)
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("얕은 문서.txt"));
    }

    @Test
    @DisplayName("보관 기간이 지난 폴더는 자동 비우기에서 영구 삭제된다")
    void purgesExpiredFolders() throws Exception {
        Tree t = tree("fexpire");
        t.s().delete("/api/folders/{id}", t.a()).andExpect(status().isNoContent());
        jdbc.update("update folders set deleted_at = ? where folder_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(40))), t.a());

        trashService.purgeExpired();

        assertThat(jdbc.queryForObject("select count(*) from folders where folder_id in (?, ?)", Integer.class, t.a(), t.b())).isZero();
    }

    @Test
    @DisplayName("팀 휴지통의 폴더는 삭제 권한이 있는 멤버만 복원·영구 삭제할 수 있다")
    void teamTrashRequiresDeletePermission() throws Exception {
        Api.Session leader = api().signUp("ftleader");
        Api.Session member = api().signUp("ftmember");
        long[] team = leader.createTeam("휴지통 팀");
        leader.postJson("/api/teams/{t}/invitations", Map.of("username", member.username), team[0]).andExpect(status().isCreated());
        long invitation = ((Number) Api.read(member.get("/api/notifications"), "$.items[0].invitationId")).longValue();
        member.post("/api/invitations/{id}/accept", invitation).andExpect(status().isNoContent());
        long folder = leader.createFolder(team[1], "회의 자료");
        leader.delete("/api/folders/{id}", folder).andExpect(status().isNoContent());

        member.post("/api/trash/folders/{id}/restore", folder).andExpect(status().isForbidden());
        member.delete("/api/trash/folders/{id}", folder).andExpect(status().isForbidden());
        leader.get("/api/trash?teamId={t}", team[0]).andExpect(jsonPath("$[0].id").value(folder));
        leader.post("/api/trash/folders/{id}/restore", folder).andExpect(status().isOk());
    }
}
