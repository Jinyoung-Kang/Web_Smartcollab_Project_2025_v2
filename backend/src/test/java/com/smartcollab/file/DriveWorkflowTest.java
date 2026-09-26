package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriveWorkflowTest extends IntegrationTest {

    @Test
    @DisplayName("업로드 → 폴더 내용 → 다운로드(스트리밍, 원본 파일명 유지)")
    void uploadListDownload() throws Exception {
        Api.Session s = api().signUp("drive");
        long fileId = s.uploadText(s.rootFolderId, "my report_final v2.txt", "hello world");

        s.get("/api/folders/{id}", s.rootFolderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder.root").value(true))
                .andExpect(jsonPath("$.path[0].name").value("내 드라이브"))
                .andExpect(jsonPath("$.items[0].name").value("my report_final v2.txt"))
                .andExpect(jsonPath("$.items[0].size").value(11))
                .andExpect(jsonPath("$.permissions.canEdit").value(true));

        // v1 은 공백을 '+' 로, 공유 다운로드에서는 첫 '_' 앞부분을 잘라 파일명이 깨졌습니다.
        s.get("/api/files/{id}/download", fileId)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''my%20report_final%20v2.txt")))
                .andExpect(content().string("hello world"));
    }

    @Test
    @DisplayName("미리보기는 안전한 형식만 inline, HTML 은 항상 첨부파일로 (저장형 XSS 방지)")
    void inlineOnlyForSafeTypes() throws Exception {
        Api.Session s = api().signUp("inline");
        long txt = s.uploadText(s.rootFolderId, "note.md", "# md");
        long html = s.uploadText(s.rootFolderId, "evil.html", "<script>alert(1)</script>");
        s.get("/api/files/{id}/view", txt)
                .andExpect(header().string("Content-Type", containsString("text/plain")))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        s.get("/api/files/{id}/view", html)
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    @DisplayName("휴지통: 삭제 → 목록 → 복원 → 다시 삭제 → 영구 삭제 시 저장소 파일도 제거")
    void trashLifecycle() throws Exception {
        Api.Session s = api().signUp("trash");
        long fileId = s.uploadText(s.rootFolderId, "a.txt", "aaa");
        String key = jdbc.queryForObject("select stored_path from file_versions where file_id = ?", String.class, fileId);
        assertThat(Files.exists(storageRoot().resolve(key))).isTrue();

        s.delete("/api/files/{id}", fileId).andExpect(status().isNoContent());
        s.get("/api/folders/{id}", s.rootFolderId).andExpect(jsonPath("$.items", hasSize(0)));
        s.get("/api/trash").andExpect(jsonPath("$[0].id").value(fileId)).andExpect(jsonPath("$[0].deletedByName").exists());

        s.post("/api/trash/{id}/restore", fileId).andExpect(status().isNoContent());
        s.get("/api/folders/{id}", s.rootFolderId).andExpect(jsonPath("$.items", hasSize(1)));

        s.delete("/api/files/{id}", fileId).andExpect(status().isNoContent());
        s.delete("/api/trash/{id}", fileId).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from files where file_id = ?", Integer.class, fileId)).isZero();
        assertThat(Files.exists(storageRoot().resolve(key))).as("커밋 후 저장소 파일 삭제").isFalse();
    }

    @Test
    @DisplayName("[v1 버그] 휴지통 파일·서명된 파일이 들어 있는 폴더도 삭제된다 (v1: FK 오류)")
    void deleteFolderWithTrashedAndSignedFiles() throws Exception {
        Api.Session s = api().signUp("folderdel");
        long folder = s.createFolder(s.rootFolderId, "보관함");
        long sub = s.createFolder(folder, "하위");
        long trashed = s.uploadText(sub, "old.txt", "old");
        long signed = s.uploadText(folder, "contract.txt", "sign me");
        s.delete("/api/files/{id}", trashed).andExpect(status().isNoContent());
        s.post("/api/files/{id}/signatures", signed).andExpect(status().isCreated());
        s.postJson("/api/files/{id}/share-links", Map.of(), signed).andExpect(status().isCreated());

        s.delete("/api/folders/{id}", folder).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from folders where folder_id in (?, ?)", Integer.class, folder, sub)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from files where file_id in (?, ?)", Integer.class, trashed, signed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from signatures where file_id = ?", Integer.class, signed)).isZero();
    }

    @Test
    @DisplayName("[v1 버그] 복사한 파일도 내려받을 수 있다 (v1: 복사본에 버전이 없어 다운로드 실패)")
    void copiedFileIsDownloadable() throws Exception {
        Api.Session s = api().signUp("copy");
        long src = s.uploadText(s.rootFolderId, "원본.txt", "copy me");
        long dest = s.createFolder(s.rootFolderId, "사본들");
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "file", "id", src)), "targetFolderId", dest))
                .andExpect(jsonPath("$.copiedFiles").value(1));
        long copyId = ((Number) Api.read(s.get("/api/folders/{id}", dest), "$.items[0].id")).longValue();
        s.get("/api/files/{id}/download", copyId).andExpect(status().isOk()).andExpect(content().string("copy me"));
    }

    @Test
    @DisplayName("같은 폴더에 복사하면 이름에 번호를 붙이고, 폴더는 하위 구조까지 복사된다")
    void folderCopyIsRecursive() throws Exception {
        Api.Session s = api().signUp("deepcopy");
        long a = s.createFolder(s.rootFolderId, "A");
        long b = s.createFolder(a, "B");
        s.uploadText(a, "1.txt", "one");
        s.uploadText(b, "2.txt", "two");
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "folder", "id", a)), "targetFolderId", s.rootFolderId))
                .andExpect(jsonPath("$.copiedFiles").value(2));
        String body = Api.body(s.get("/api/folders/{id}", s.rootFolderId));
        assertThat(body).contains("\"name\":\"A\"").contains("\"name\":\"A (1)\"");
        String tree = Api.body(s.get("/api/folders/tree"));
        assertThat(tree.split("\"name\":\"B\"", -1)).hasSize(3);   // 원본 B + 복사본 B
    }

    @Test
    @DisplayName("[v1 버그] 폴더를 자기 하위 폴더로 옮기면 거절된다 (v1: 순환 구조 → 트리 무한 재귀)")
    void moveIntoOwnSubtreeIsRejected() throws Exception {
        Api.Session s = api().signUp("cycle");
        long parent = s.createFolder(s.rootFolderId, "부모");
        long child = s.createFolder(parent, "자식");
        s.postJson("/api/items/move", Map.of("items", List.of(Map.of("type", "folder", "id", parent)), "targetFolderId", child))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("하위 폴더")));
        s.postJson("/api/items/move", Map.of("items", List.of(Map.of("type", "folder", "id", parent)), "targetFolderId", parent))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[v1 버그] 개인 ↔ 팀 스토리지 간 이동은 거절된다 (v1: 폴더와 내부 파일의 소속이 어긋남)")
    void crossScopeMoveIsRejected() throws Exception {
        Api.Session s = api().signUp("scope");
        long[] team = s.createTeam("팀");
        long file = s.uploadText(s.rootFolderId, "개인.txt", "p");
        s.postJson("/api/items/move", Map.of("items", List.of(Map.of("type", "file", "id", file)), "targetFolderId", team[1]))
                .andExpect(status().isBadRequest());
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "file", "id", file)), "targetFolderId", team[1]))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("루트 폴더는 이름 변경·삭제·이동할 수 없다")
    void rootFolderIsProtected() throws Exception {
        Api.Session s = api().signUp("root");
        s.patchJson("/api/folders/{id}", Map.of("name", "x"), s.rootFolderId).andExpect(status().isBadRequest());
        s.delete("/api/folders/{id}", s.rootFolderId).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이름 검증: 경로 구분자·빈 이름은 거절")
    void nameValidation() throws Exception {
        Api.Session s = api().signUp("names");
        s.postJson("/api/folders", Map.of("parentId", s.rootFolderId, "name", "../etc")).andExpect(status().isBadRequest());
        s.postJson("/api/folders", Map.of("parentId", s.rootFolderId, "name", "  ")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검색: 하위 폴더까지 이름 부분 일치, 경로 표시, LIKE 와일드카드는 문자 그대로")
    void searchAcrossSubfolders() throws Exception {
        Api.Session s = api().signUp("search");
        long docs = s.createFolder(s.rootFolderId, "문서");
        long y = s.createFolder(docs, "2026");
        s.uploadText(y, "회의록_9월.md", "x");
        s.uploadText(s.rootFolderId, "100%_완료.txt", "x");
        s.uploadText(s.rootFolderId, "100X완료.txt", "x");
        s.get("/api/files/search?q=회의록")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].path").value("/문서/2026"));
        s.get("/api/files/search?q=100%_").andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("사용량: 휴지통을 제외한 파일 수와 바이트")
    void usage() throws Exception {
        Api.Session s = api().signUp("usage");
        s.uploadText(s.rootFolderId, "a.txt", "12345");
        long b = s.uploadText(s.rootFolderId, "b.txt", "123");
        s.delete("/api/files/{id}", b);
        s.get("/api/files/usage").andExpect(jsonPath("$.fileCount").value(1)).andExpect(jsonPath("$.totalBytes").value(5));
    }

    @Test
    @DisplayName("업로드 시 SHA-256 을 계산해 버전에 기록한다")
    void sha256Recorded() throws Exception {
        Api.Session s = api().signUp("hash");
        long id = s.uploadText(s.rootFolderId, "h.txt", "abc");
        s.get("/api/files/{id}/versions", id)
                .andExpect(jsonPath("$[0].sha256").value("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
                .andExpect(jsonPath("$[0].active").value(true));
        assertThat("abc".getBytes(StandardCharsets.UTF_8)).hasSize(3);
    }
}
