package com.smartcollab.folder;

import com.jayway.jsonpath.JsonPath;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [IMP-02] 폴더 목록에 페이지가 없어 항목 1만 개 폴더가 JSON 2.31MB 를 한 번에 돌려주었습니다(출시 기준 QA). 목록을 정해진 수만큼
 * 나눠 주고(기본 500·최대 1,000), 다음 묶음은 커서로 이어 받습니다. 정렬(폴더 우선, 한국어 자연 정렬)은 서버가 정합니다.
 */
class FolderPagingTest extends IntegrationTest {

    private static List<String> names(String body) {
        return JsonPath.read(body, "$.items[*].name");
    }

    @Test
    @DisplayName("[IMP-02] limit 만큼 나눠 주고, 커서로 이어 받으면 빠짐·겹침 없이 끝까지 온다 (폴더 우선·자연 정렬)")
    void pagesThroughFolder() throws Exception {
        Api.Session s = api().signUp("imp02p");
        long dir = s.createFolder(s.rootFolderId, "목록");
        for (String f : List.of("나 폴더", "가 폴더")) s.createFolder(dir, f);
        for (String f : List.of("보고서 10.txt", "보고서 2.txt", "보고서 1.txt", "가.txt", "B.txt", "a.txt")) s.uploadText(dir, f, "x");

        List<String> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String body = Api.body(cursor == null
                    ? s.get("/api/folders/{id}?limit=3", dir)
                    : s.get("/api/folders/{id}?limit=3&cursor={c}", dir, cursor));
            assertThat((Integer) JsonPath.read(body, "$.itemCount")).isEqualTo(8);
            assertThat(names(body)).hasSizeLessThanOrEqualTo(3);
            all.addAll(names(body));
            cursor = JsonPath.read(body, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(all).containsExactly("가 폴더", "나 폴더", "a.txt", "B.txt", "가.txt", "보고서 1.txt", "보고서 2.txt", "보고서 10.txt");
    }

    @Test
    @DisplayName("[IMP-02] 정렬 기준·방향을 서버가 적용한다 (폴더는 늘 앞)")
    void sortsOnServer() throws Exception {
        Api.Session s = api().signUp("imp02s");
        long dir = s.createFolder(s.rootFolderId, "정렬");
        s.createFolder(dir, "폴더");
        s.upload(dir, "작은.bin", new byte[10]);
        s.upload(dir, "큰.bin", new byte[300]);
        s.upload(dir, "중간.bin", new byte[100]);

        String body = Api.body(s.get("/api/folders/{id}?sort=size&order=desc", dir));

        assertThat(names(body)).containsExactly("폴더", "큰.bin", "중간.bin", "작은.bin");
        assertThat((Object) JsonPath.read(body, "$.nextCursor")).isNull();
    }

    @Test
    @DisplayName("[IMP-02] 아무 값도 주지 않으면 500개까지만 주고 다음 커서를 준다")
    void defaultPageSize() throws Exception {
        Api.Session s = api().signUp("imp02d");
        long dir = s.createFolder(s.rootFolderId, "많은 폴더");
        for (int i = 0; i < 503; i++) s.createFolder(dir, "하위 " + i);

        String body = Api.body(s.get("/api/folders/{id}", dir));

        assertThat(names(body)).hasSize(500);
        assertThat((Integer) JsonPath.read(body, "$.itemCount")).isEqualTo(503);
        assertThat((String) JsonPath.read(body, "$.nextCursor")).isNotBlank();
    }

    @Test
    @DisplayName("[IMP-02] 잘못된 limit·커서·정렬 기준은 400")
    void rejectsBadParameters() throws Exception {
        Api.Session s = api().signUp("imp02b");
        for (String q : List.of("limit=0", "limit=1001", "limit=abc", "cursor=!!!", "cursor=bm90LWFuLW9mZnNldA", "sort=password", "order=sideways")) {
            s.get("/api/folders/{id}?" + q, s.rootFolderId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }
}
