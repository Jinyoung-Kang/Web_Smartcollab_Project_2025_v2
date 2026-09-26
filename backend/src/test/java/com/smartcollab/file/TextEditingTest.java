package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TextEditingTest extends IntegrationTest {

    @Test
    @DisplayName("[v1 버그: lost update] 다른 사람이 먼저 저장했으면 오래된 버전 기준 저장은 409")
    void staleSaveIsRejected() throws Exception {
        Api.Session s = api().signUp("edit");
        long file = s.uploadText(s.rootFolderId, "doc.md", "v1");
        long base = ((Number) Api.read(s.get("/api/files/{id}/content", file), "$.versionId")).longValue();

        s.putJson("/api/files/{id}/content", Map.of("content", "A의 수정", "baseVersionId", base), file).andExpect(status().isOk());
        s.putJson("/api/files/{id}/content", Map.of("content", "B의 수정", "baseVersionId", base), file)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));

        s.get("/api/files/{id}/content", file).andExpect(jsonPath("$.content").value("A의 수정"));
        s.get("/api/files/{id}/versions", file).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    @DisplayName("버전 복원: 이전 버전이 현재 버전이 되고 기록에 active 로 표시된다")
    void restoreVersion() throws Exception {
        Api.Session s = api().signUp("restore");
        long file = s.uploadText(s.rootFolderId, "doc.txt", "first");
        long v1 = ((Number) Api.read(s.get("/api/files/{id}/content", file), "$.versionId")).longValue();
        s.putJson("/api/files/{id}/content", Map.of("content", "second", "baseVersionId", v1), file);

        s.post("/api/files/{f}/versions/{v}/restore", file, v1).andExpect(status().isNoContent());
        s.get("/api/files/{id}/content", file).andExpect(jsonPath("$.content").value("first"))
                .andExpect(jsonPath("$.versionId").value(v1));
        s.get("/api/files/{id}/versions", file).andExpect(jsonPath("$[1].active").value(true));
    }

    @Test
    @DisplayName("[v1 버그] 서명은 '현재' 버전에 붙고, 내용이 바뀌면 무효가 된다")
    void signatureFollowsActiveVersion() throws Exception {
        Api.Session s = api().signUp("sign");
        long file = s.uploadText(s.rootFolderId, "계약.txt", "초안");
        long v1 = ((Number) Api.read(s.get("/api/files/{id}/content", file), "$.versionId")).longValue();
        s.putJson("/api/files/{id}/content", Map.of("content", "수정안", "baseVersionId", v1), file);
        // 옛 버전을 복원한 뒤 서명 → v1 에 서명되어야 함 (v1 코드는 가장 최근 생성 버전에 서명)
        s.post("/api/files/{f}/versions/{v}/restore", file, v1);
        s.post("/api/files/{id}/signatures", file).andExpect(status().isCreated());
        s.get("/api/files/{id}/versions", file)
                .andExpect(jsonPath("$[1].versionId").value(v1))
                .andExpect(jsonPath("$[1].signatures[0].valid").value(true))
                .andExpect(jsonPath("$[0].signatures", hasSize(0)));

        s.post("/api/files/{id}/signatures", file).andExpect(status().isConflict());

        s.putJson("/api/files/{id}/content", Map.of("content", "재수정", "baseVersionId", v1), file);
        s.get("/api/files/{id}/versions", file).andExpect(jsonPath("$[2].signatures[0].valid").value(false));
    }

    @Test
    @DisplayName("텍스트가 아닌 파일은 편집 API 로 열 수 없다")
    void nonTextRejected() throws Exception {
        Api.Session s = api().signUp("bin");
        long file = Api.id(s.upload(s.rootFolderId, "image.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
        s.get("/api/files/{id}/content", file).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("요약: 생성형 AI 가 아닌 추출 요약이며 원문 문장만 돌려준다")
    void summaryIsExtractive() throws Exception {
        Api.Session s = api().signUp("summary");
        String text = """
                파일 협업 도구는 팀의 문서를 한 곳에 모은다.
                문서 협업에서 가장 중요한 것은 최신 문서를 빠르게 찾는 것이다.
                점심 메뉴는 김치찌개였다.
                버전 기록이 있으면 문서의 변경 이력을 추적할 수 있다.
                """;
        long file = s.uploadText(s.rootFolderId, "요약.txt", text);
        String body = Api.body(s.post("/api/files/{id}/summary", file));
        org.assertj.core.api.Assertions.assertThat(body).contains("extractive-term-frequency").doesNotContain("김치찌개");
    }

    @Test
    @DisplayName("번역 키가 없으면 가짜 결과 대신 503 FEATURE_DISABLED (v1: '[MOCK]' 문자열을 결과처럼 표시)")
    void translationWithoutKeyIsDisabled() throws Exception {
        Api.Session s = api().signUp("translate");
        long file = s.uploadText(s.rootFolderId, "t.txt", "안녕하세요");
        s.post("/api/files/{id}/translation?target=EN", file)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
    }
}
