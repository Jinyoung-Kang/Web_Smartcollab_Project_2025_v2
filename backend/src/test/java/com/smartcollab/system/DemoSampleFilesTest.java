package com.smartcollab.system;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 체험 계정마다 내 드라이브에 '파일 유형별 시험' 폴더(미리보기 종류별 표본 파일)를 만들고, 매일 초기화한 뒤에도 다시 만든다.
 * 표본은 약 190KB 라 데모 한도를 작게 둔 다른 시험(LimitedStorageIntegrationTest)에서는 끕니다.
 */
@TestPropertySource(properties = {
        "app.demo.enabled=true",
        "app.demo.password=" + DemoSampleFilesTest.PASSWORD,
        "app.demo.quota=5MB",
        "app.demo.sample-files=true",
})
class DemoSampleFilesTest extends IntegrationTest {

    static final String PASSWORD = "demo-pass-2";

    /** 화면에 보일 이름 → 서버가 분류하는 미리보기 종류 */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        for (String n : List.of("메모.txt", "회의록.md", "서버.log", "요구사항.csv", "설정.json")) EXPECTED.put(n, "TEXT");
        EXPECTED.put("보고서.pdf", "PDF");
        for (String n : List.of("입사 안내.docx", "예산.xlsx", "발표.pptx")) EXPECTED.put(n, "OFFICE");
        for (String n : List.of("사진.png", "사진.jpg", "애니메이션.gif", "사진.webp", "사진.bmp")) EXPECTED.put(n, "IMAGE");
        for (String n : List.of("묶음.zip", "데이터.bin")) EXPECTED.put(n, "NONE");
    }

    @Autowired
    DemoDataSeeder seeder;

    @Test
    @DisplayName("체험 계정 셋 모두 내 드라이브에 파일 유형별 표본이 있고, 초기화한 뒤에도 다시 생긴다")
    void everyDemoAccountHasSampleFiles() throws Exception {
        seeder.reset();   // 체험 데이터는 DB 에 하나뿐 — 다른 설정의 시험 문맥이 만든 것이 남아 있을 수 있어 이 설정으로 다시 만듦
        assertSamples();
        seeder.reset();
        assertSamples();
    }

    private void assertSamples() throws Exception {
        for (String username : List.of("demo1", "demo2", "demo3")) {
            Api.Session s = api().login(username, PASSWORD);
            List<Number> folders = Api.read(s.get("/api/folders/{id}", s.rootFolderId), "$.items[?(@.name == '파일 유형별 시험')].id");
            assertThat(folders).as("%s 의 시험 폴더", username).hasSize(1);
            long folder = folders.getFirst().longValue();
            Map<String, String> actual = new LinkedHashMap<>();
            List<Map<String, Object>> items = Api.read(s.get("/api/folders/{id}", folder), "$.items");
            items.forEach(i -> actual.put((String) i.get("name"), (String) i.get("previewKind")));
            assertThat(actual).as("%s 의 표본 파일", username).containsExactlyInAnyOrderEntriesOf(EXPECTED);

            List<Number> pdf = Api.read(s.get("/api/folders/{id}", folder), "$.items[?(@.name == '보고서.pdf')].id");
            s.send(MockMvcRequestBuilders.get("/api/files/{id}/download", pdf.getFirst().longValue()))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(new ClassPathResource("demo/samples/report.pdf").getContentAsByteArray()));
        }
    }
}
