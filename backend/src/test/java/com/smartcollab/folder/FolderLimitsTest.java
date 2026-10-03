package com.smartcollab.folder;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [S-04·S-05] 폴더 수·깊이 상한. 이전에는 복사가 파일 수만 세고 같은 항목의 중복도 막지 않아 요청 몇 번으로 폴더 수백만 개를
 * 한 트랜잭션에서 만들 수 있었고, 깊이 제한이 없어 MySQL 재귀 쿼리 한도(1000단계)를 넘기면 그 폴더와 팀·계정을 지울 수 없었습니다.
 */
class FolderLimitsTest extends IntegrationTest {

    static final int MAX_DEPTH = 50;   // application.yml 기본값

    @Autowired
    FolderRepository folders;
    @Autowired
    UserRepository users;
    @Autowired
    TransactionTemplate tx;

    /** parent 아래로 count 단계 사슬을 만들고, 각 단계 폴더 ID 를 깊이 순서로 돌려줍니다. */
    private List<Long> chain(long parentId, long userId, int count) {
        return tx.execute(st -> {
            User user = users.getReferenceById(userId);
            Folder p = folders.findById(parentId).orElseThrow();
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                p = folders.save(Folder.childOf(p, "단계" + i, user));
                ids.add(p.getId());
            }
            return ids;
        });
    }

    @Test
    @DisplayName("[S-05] 폴더는 최상위 아래 50단계까지만 만들 수 있다")
    void createDepthLimit() throws Exception {
        Api.Session s = api().signUp("fdepth");
        List<Long> c = chain(s.rootFolderId, s.userId, MAX_DEPTH);
        s.postJson("/api/folders", Map.of("parentId", c.get(MAX_DEPTH - 2), "name", "50단계")).andExpect(status().is2xxSuccessful());
        s.postJson("/api/folders", Map.of("parentId", c.get(MAX_DEPTH - 1), "name", "51단계"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("폴더는 최상위 아래 50단계까지만 만들 수 있습니다."));
    }

    @Test
    @DisplayName("[S-05] 옮긴 뒤 하위 폴더까지 50단계를 넘으면 이동·복사를 거절한다")
    void moveAndCopyDepthLimit() throws Exception {
        Api.Session s = api().signUp("fmovedepth");
        List<Long> deep = chain(s.rootFolderId, s.userId, MAX_DEPTH - 1);            // 49단계
        List<Long> sub = chain(s.rootFolderId, s.userId, 2);                          // 높이 1 인 트리(1·2단계)
        Map<String, Object> item = Map.of("type", "folder", "id", sub.get(0));
        s.postJson("/api/items/move", Map.of("items", List.of(item), "targetFolderId", deep.get(MAX_DEPTH - 2)))
                .andExpect(status().isBadRequest());
        s.postJson("/api/items/copy", Map.of("items", List.of(item), "targetFolderId", deep.get(MAX_DEPTH - 2)))
                .andExpect(status().isBadRequest());
        s.postJson("/api/items/move", Map.of("items", List.of(item), "targetFolderId", deep.get(MAX_DEPTH - 3)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("[S-04] 같은 폴더를 여러 번 넣어 복사해도 한 번만 복사한다")
    void copyDeduplicatesItems() throws Exception {
        Api.Session s = api().signUp("fcopydup");
        long f = s.createFolder(s.rootFolderId, "원본");
        long dest = s.createFolder(s.rootFolderId, "대상");
        Map<String, Object> item = Map.of("type", "folder", "id", f);
        s.postJson("/api/items/copy", Map.of("items", List.of(item, item, item), "targetFolderId", dest))
                .andExpect(status().is2xxSuccessful());
        s.get("/api/folders/{id}", dest).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @DisplayName("[S-04] 한 번에 복사하는 폴더는 1,000개까지 — 넘으면 아무것도 만들지 않고 400")
    void copyFolderCountLimit() throws Exception {
        Api.Session s = api().signUp("fcopywide");
        long wide = s.createFolder(s.rootFolderId, "넓은 폴더");
        long dest = s.createFolder(s.rootFolderId, "대상");
        tx.executeWithoutResult(st -> {
            User user = users.getReferenceById(s.userId);
            Folder parent = folders.findById(wide).orElseThrow();
            List<Folder> children = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                children.add(Folder.childOf(parent, "하위" + i, user));
            }
            folders.saveAll(children);
        });
        s.postJson("/api/items/copy", Map.of("items", List.of(Map.of("type", "folder", "id", wide)), "targetFolderId", dest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("한 번에 복사할 수 있는 폴더는 1000개까지입니다."));
        assertThat(jdbc.queryForObject("select count(*) from folders where parent_folder_id = ?", Integer.class, dest)).isZero();
    }
}
