package com.smartcollab.file;

import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [PERF-03] 여러 항목 삭제를 한 요청으로. 이전에는 선택한 항목마다 요청·트랜잭션·실시간 알림이 하나씩 생겼습니다
 * (50개를 지우면 요청 50번, 같은 폴더의 변경 알림 50번 → 팀원 화면이 폴더를 50번 다시 불러옴).
 */
@RecordApplicationEvents
class ItemDeletionTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    @Test
    @DisplayName("파일과 폴더를 한 요청으로 지운다 — 파일은 휴지통으로, 폴더는 안의 파일까지 영구 삭제")
    void deletesFilesAndFoldersInOneRequest() throws Exception {
        Api.Session s = api().signUp("bulkdel");
        long a = s.uploadText(s.rootFolderId, "a.txt", "a");
        long b = s.uploadText(s.rootFolderId, "b.txt", "b");
        long folder = s.createFolder(s.rootFolderId, "보관");
        s.uploadText(folder, "inside.txt", "inside");

        s.postJson("/api/items/delete", Map.of("items", List.of(ref("file", a), ref("file", b), ref("folder", folder))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trashedFiles").value(2))
                .andExpect(jsonPath("$.deletedFolders").value(1));

        s.get("/api/folders/{id}", s.rootFolderId).andExpect(jsonPath("$.items", hasSize(0)));
        s.get("/api/trash").andExpect(jsonPath("$", hasSize(2)));
        s.get("/api/folders/{id}", folder).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("하나라도 지울 수 없으면 아무것도 지우지 않는다 (한 트랜잭션)")
    void allOrNothing() throws Exception {
        Api.Session owner = api().signUp("bulkown");
        Api.Session other = api().signUp("bulkoth");
        long mine = owner.uploadText(owner.rootFolderId, "mine.txt", "m");
        long theirs = other.uploadText(other.rootFolderId, "theirs.txt", "t");

        owner.postJson("/api/items/delete", Map.of("items", List.of(ref("file", mine), ref("file", theirs))))
                .andExpect(status().isNotFound());

        owner.get("/api/folders/{id}", owner.rootFolderId).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    @DisplayName("팀 폴더의 변경 알림은 지운 항목 수와 상관없이 폴더당 한 번만 보낸다")
    void publishesOneChangePerFolder() throws Exception {
        Api.Session leader = api().signUp("bulkteam");
        long[] team = leader.createTeam("일괄 삭제 팀");
        List<Map<String, Object>> refs = IntStream.range(0, 5)
                .mapToObj(i -> ref("file", leader.uploadText(team[1], "f" + i + ".txt", "x"))).toList();
        events.clear();

        leader.postJson("/api/items/delete", Map.of("items", refs)).andExpect(status().isOk());

        assertThat(events.stream(RealtimeEvents.FolderChanged.class))
                .containsExactly(new RealtimeEvents.FolderChanged(team[0], team[1]));
    }

    @Test
    @DisplayName("최상위 폴더는 지울 수 없고, 빈 목록·200개 초과는 거절한다")
    void validates() throws Exception {
        Api.Session s = api().signUp("bulkval");

        s.postJson("/api/items/delete", Map.of("items", List.of(ref("folder", s.rootFolderId)))).andExpect(status().isBadRequest());
        s.postJson("/api/items/delete", Map.of("items", List.of())).andExpect(status().isBadRequest());
        List<Map<String, Object>> tooMany = IntStream.range(0, 201).mapToObj(i -> ref("file", (long) i)).toList();
        s.postJson("/api/items/delete", Map.of("items", tooMany)).andExpect(status().isBadRequest());
    }

    private static Map<String, Object> ref(String type, long id) {
        return Map.of("type", type, "id", id);
    }
}
