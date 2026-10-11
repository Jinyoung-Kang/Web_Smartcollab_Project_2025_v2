package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.smartcollab.ai.DocxFixtures.docx;
import static com.smartcollab.ai.DocxFixtures.paragraph;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [AI-01·02·03·05] 문서 요약·번역 API. AI(Claude)는 가짜로 바꿔 과금 없이 시험합니다 — 요청 모양은 ClaudeDocumentAiTest 가 봅니다.
 * <ul>
 *   <li>키가 있으면 Claude 로 생성형 요약·번역, 없으면 추출 요약·DeepL(텍스트만)로 동작합니다.</li>
 *   <li>권한을 가장 먼저 보고(볼 수 없으면 404, AI 를 부르지 않음), 텍스트·PDF·Word(docx)만 받습니다.</li>
 *   <li>입력 토큰 상한과 사용자별 하루 토큰 한도를 호출 전에 확인하고, 실제 사용량으로 정산합니다. 실패하면 예약분을 돌려줍니다.</li>
 *   <li>같은 버전의 같은 요청은 다시 과금하지 않습니다(결과 캐시). 새 버전을 저장하면 다시 부릅니다.</li>
 * </ul>
 */
@TestPropertySource(properties = "app.ai.tokens-per-user-per-day=12000")
class DocumentToolsTest extends IntegrationTest {

    @MockitoBean
    DocumentAi documentAi;

    @org.springframework.beans.factory.annotation.Autowired
    com.smartcollab.global.security.SlidingWindowRateLimiter rateLimiter;

    @BeforeEach
    void enableAi() {
        reset(documentAi);
        when(documentAi.enabled()).thenReturn(true);
        when(documentAi.model()).thenReturn("claude-haiku-5-5");
        when(documentAi.countSummaryTokens(any())).thenReturn(100L);
        when(documentAi.countTranslationTokens(any(), any())).thenReturn(100L);
        when(documentAi.summarize(any())).thenReturn(new DocumentAi.Output<>(
                new DocumentAi.Summary("회의 결과를 정리한 문서입니다.", List.of("일정 확정", "권한 정리")), 100, 50));
        when(documentAi.translate(any(), any(), anyLong())).thenReturn(new DocumentAi.Output<>("Translated", 100, 30));
    }

    @Test
    @DisplayName("[AI-01] 키가 있으면 Claude 생성형 요약 — 요약 문단·핵심 항목·모델을 돌려주고, 문서 내용을 그대로 넘긴다")
    void generativeSummary() throws Exception {
        Api.Session s = api().signUp("ai01s");
        long file = s.uploadText(s.rootFolderId, "회의록.md", "# 회의\n일정을 확정했다.");

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("generative"))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.summary").value("회의 결과를 정리한 문서입니다."))
                .andExpect(jsonPath("$.sentences[0]").value("일정 확정"))
                .andExpect(jsonPath("$.totalSentences").doesNotExist());   // null 은 응답에서 빠짐(non_null)

        ArgumentCaptor<AiDocument> doc = ArgumentCaptor.forClass(AiDocument.class);
        verify(documentAi).summarize(doc.capture());
        assertThat(doc.getValue().text()).isEqualTo("# 회의\n일정을 확정했다.");
        assertThat(doc.getValue().title()).isEqualTo("회의록.md");
    }

    @Test
    @DisplayName("[AI-01] 번역 대상은 EN·KO·JA·ZH, Claude 로 번역하고 공급자·모델을 알린다. 그 밖의 언어는 400")
    void claudeTranslation() throws Exception {
        Api.Session s = api().signUp("ai01t");
        long file = s.uploadText(s.rootFolderId, "a.txt", "안녕하세요");

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=ja", file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Translated"))
                .andExpect(jsonPath("$.targetLang").value("JA"))
                .andExpect(jsonPath("$.provider").value("claude"))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"));
        verify(documentAi).translate(any(), org.mockito.ArgumentMatchers.eq(TargetLanguage.JA), anyLong());

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=FR", file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("[AI-01] 볼 수 없는 파일은 404 이고 AI 를 부르지 않는다(토큰 세기도 안 함)")
    void strangerGets404WithoutCallingAi() throws Exception {
        Api.Session owner = api().signUp("ai01o");
        Api.Session stranger = api().signUp("ai01x");
        long file = owner.uploadText(owner.rootFolderId, "비밀.txt", "비밀 내용");

        stranger.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file)).andExpect(status().isNotFound());
        stranger.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", file)).andExpect(status().isNotFound());

        verify(documentAi, never()).countSummaryTokens(any());
        verify(documentAi, never()).countTranslationTokens(any(), any());
    }

    @Test
    @DisplayName("[AI-02] PDF 는 원본 그대로, Word(docx)는 뽑은 본문 텍스트로 넘기고, 그 밖의 형식(이미지 등)은 400")
    void pdfAndDocx() throws Exception {
        Api.Session s = api().signUp("ai02");
        byte[] pdf = "%PDF-1.4 테스트".getBytes(StandardCharsets.UTF_8);
        long pdfFile = Api.id(s.upload(s.rootFolderId, "보고서.pdf", pdf));
        long docxFile = Api.id(s.upload(s.rootFolderId, "기획.docx", docx(paragraph("워드 본문") + paragraph("둘째 문단"))));
        long png = Api.id(s.upload(s.rootFolderId, "그림.png", new byte[]{1, 2, 3}));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", pdfFile)).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", docxFile)).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", png))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        ArgumentCaptor<AiDocument> summarized = ArgumentCaptor.forClass(AiDocument.class);
        verify(documentAi).summarize(summarized.capture());
        assertThat(summarized.getValue().isPdf()).isTrue();
        assertThat(summarized.getValue().pdf()).isEqualTo(pdf);
        ArgumentCaptor<AiDocument> translated = ArgumentCaptor.forClass(AiDocument.class);
        verify(documentAi).translate(translated.capture(), any(), anyLong());
        assertThat(translated.getValue().text()).isEqualTo("워드 본문\n둘째 문단");
    }

    @Test
    @DisplayName("[AI-01] 키가 없으면 텍스트·Word 요약은 추출 요약으로, PDF 요약과 번역(DeepL 키도 없음)은 503")
    void fallsBackWithoutKey() throws Exception {
        when(documentAi.enabled()).thenReturn(false);
        Api.Session s = api().signUp("ai01f");
        long text = s.uploadText(s.rootFolderId, "a.txt", "보안 점검을 했다. 보안 결과를 공유했다. 점심을 먹었다.");
        long word = Api.id(s.upload(s.rootFolderId, "b.docx", docx(paragraph("보안 점검을 했다. 보안 결과를 공유했다."))));
        long pdf = Api.id(s.upload(s.rootFolderId, "c.pdf", "%PDF-1.4".getBytes()));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", text))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("extractive-term-frequency"))
                .andExpect(jsonPath("$.totalSentences").value(3));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", word))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("extractive-term-frequency"));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", pdf))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", text))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
        verify(documentAi, never()).summarize(any());
    }

    @Test
    @DisplayName("[AI-03] 입력이 상한(요약 100,000·번역 32,000 토큰)을 넘으면 AI 를 부르지 않고 413")
    void inputTokenCaps() throws Exception {
        Api.Session s = api().signUp("ai03c");
        long file = s.uploadText(s.rootFolderId, "긴 문서.txt", "본문");
        when(documentAi.countSummaryTokens(any())).thenReturn(100_001L);
        when(documentAi.countTranslationTokens(any(), any())).thenReturn(32_001L);

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", file))
                .andExpect(status().is(413));
        verify(documentAi, never()).summarize(any());
        verify(documentAi, never()).translate(any(), any(), anyLong());
    }

    @Test
    @DisplayName("[AI-03] 하루 토큰 한도는 호출 전 예약 → 실제 사용량으로 정산하고, 넘으면 429. 호출이 실패하면 예약분을 돌려준다")
    void dailyTokenBudget() throws Exception {
        Api.Session s = api().signUp("ai03b");
        long[] files = new long[4];
        for (int i = 0; i < files.length; i++) files[i] = s.uploadText(s.rootFolderId, "f" + i + ".txt", "문서 " + i);
        // 한도 12,000. 예약 = 입력 1,500 + 출력 상한 8,000(최악값, H-2), 실제 사용 = 1,500 + 200
        when(documentAi.countSummaryTokens(any())).thenReturn(1_500L);
        when(documentAi.summarize(any()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_ERROR, "실패"))
                .thenReturn(new DocumentAi.Output<>(new DocumentAi.Summary("요약", List.of("항목")), 1_500, 200));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[0])).andExpect(status().isBadGateway());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[1])).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[2])).andExpect(status().isOk());   // 정산했기에 들어감
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", files[3]))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(documentAi, times(3)).summarize(any());

        Api.Session other = api().signUp("ai03o");   // 한도는 사용자마다
        long otherFile = other.uploadText(other.rootFolderId, "g.txt", "다른 사람");
        other.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", otherFile)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[AI-05] 같은 버전의 같은 요청은 다시 부르지 않고(과금 없음), 새 버전을 저장하면 다시 부른다")
    void cachesPerVersion() throws Exception {
        Api.Session s = api().signUp("ai05");
        long file = s.uploadText(s.rootFolderId, "a.txt", "첫 내용");

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file)).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file)).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", file)).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", file)).andExpect(status().isOk());
        verify(documentAi, times(1)).summarize(any());
        verify(documentAi, times(1)).translate(any(), any(), anyLong());

        long base = ((Number) Api.read(s.get("/api/files/{id}/content", file), "$.versionId")).longValue();
        s.putJson("/api/files/{id}/content", Map.of("content", "고친 내용", "baseVersionId", base), file).andExpect(status().isOk());
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file)).andExpect(status().isOk());
        verify(documentAi, times(2)).summarize(any());
    }

    @Test
    @DisplayName("[AI-01] 공개 설정이 AI 사용 여부와 모델을 알린다(번역은 Claude·DeepL 중 하나라도 있으면 켜짐)")
    void publicConfig() throws Exception {
        api().perform(MockMvcRequestBuilders.get("/api/public/config"))
                .andExpect(jsonPath("$.aiEnabled").value(true))
                .andExpect(jsonPath("$.aiModel").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.translationEnabled").value(true));

        when(documentAi.enabled()).thenReturn(false);
        api().perform(MockMvcRequestBuilders.get("/api/public/config"))
                .andExpect(jsonPath("$.aiEnabled").value(false))
                .andExpect(jsonPath("$.translationEnabled").value(false));
    }

    @Test
    @DisplayName("[H-1] Word 본문이 편집기 한도(2MB)보다 길면 키가 있든 없든 413 — 추출 요약이 2,100만 자를 받아 힙을 다 쓰던 문제")
    void wordBodyOverTextLimitIs413() throws Exception {
        Api.Session s = api().signUp("h1docx");
        byte[] bomb = docx(paragraph("ab cd. ".repeat(320_000)));   // 224만 자, 압축하면 수 KB
        long file = Api.id(s.upload(s.rootFolderId, "폭탄.docx", bomb));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verify(documentAi, never()).countSummaryTokens(any());

        when(documentAi.enabled()).thenReturn(false);
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("[H-2] 응답을 받은 뒤의 실패는 과금된 토큰을 하루 한도에 남긴다 — 예전에는 예약을 전부 돌려줘 같은 요청을 끝없이 되풀이해 과금할 수 있었음")
    void billedFailureCountsAgainstBudget() throws Exception {
        Api.Session s = api().signUp("h2bill");
        long first = s.uploadText(s.rootFolderId, "a.txt", "첫 문서");
        long second = s.uploadText(s.rootFolderId, "b.txt", "둘째 문서");
        when(documentAi.countSummaryTokens(any())).thenReturn(1_000L);
        when(documentAi.summarize(any()))
                .thenThrow(new DocumentAi.BilledFailure(ErrorCode.PAYLOAD_TOO_LARGE, "결과가 너무 길어 끝까지 만들지 못했습니다.", 9_000L));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", first)).andExpect(status().is(413));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", second))   // 한도 12,000 중 9,000 을 이미 씀
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(documentAi, times(1)).summarize(any());
    }

    @Test
    @DisplayName("[M-1] 하루 한도를 다 쓴 사용자는 문서를 읽거나 토큰을 세기 전에 429 — 예전에는 매번 문서 전체를 읽고 토큰 세기 API 를 부른 뒤에야 거절")
    void exhaustedBudgetIsRejectedBeforeAnyWork() throws Exception {
        Api.Session s = api().signUp("m1budget");
        long file = s.uploadText(s.rootFolderId, "a.txt", "본문");
        rateLimiter.charge("ai-tokens:" + s.userId, 12_000, java.time.Duration.ofDays(1));

        s.send(MockMvcRequestBuilders.post("/api/files/{id}/summary", file))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        s.send(MockMvcRequestBuilders.post("/api/files/{id}/translation?target=EN", file))
                .andExpect(status().isTooManyRequests());
        verify(documentAi, never()).countSummaryTokens(any());
        verify(documentAi, never()).countTranslationTokens(any(), any());
    }
}
