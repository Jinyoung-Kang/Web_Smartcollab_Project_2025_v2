package com.smartcollab.ai;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.smartcollab.ai.FakeAnthropicServer.Reply;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AI-01·AI-04] Claude 요청의 모양과 응답 처리. 실제 SDK 로 로컬 가짜 서버를 부르므로 과금이 없습니다.
 * <ul>
 *   <li>모델·effort·스트리밍·구조화 출력을 정한 대로 보내고, 문서는 지시와 섞지 않고 document 블록으로 보냅니다.</li>
 *   <li>생각 블록을 건너뛰고 답(텍스트)만 읽습니다. Haiku 5.5 는 생각이 기본으로 켜져 응답이 생각 블록으로 시작합니다.</li>
 *   <li>거절·잘림·요청 한도를 각각 알맞은 오류로 바꿉니다 — 잘린 번역을 결과처럼 돌려주지 않습니다.</li>
 * </ul>
 */
class ClaudeDocumentAiTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private FakeAnthropicServer server;
    private ClaudeDocumentAi ai;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeAnthropicServer();
        AiProperties props = new AiProperties("test-key", "claude-haiku-5-5", "low", "low", 300_000, DataSize.ofMegabytes(10), Duration.ofSeconds(10),
                Duration.ofSeconds(30), 8);
        ai = new ClaudeDocumentAi(props, AnthropicOkHttpClient.builder()
                .apiKey("test-key").baseUrl(server.baseUrl()).maxRetries(0).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    /** 번역 응답(구조화 출력 {notes, translation})을 스트리밍 조각으로 — 조각 경계가 JSON 중간에 와도 SDK 가 이어 붙임 */
    private String[] translationChunks(String translation) {
        String body = json.writeValueAsString(java.util.Map.of("notes", "", "translation", translation));
        int half = body.length() / 2;
        return new String[]{body.substring(0, half), body.substring(half)};
    }

    private JsonNode lastRequest() {
        return json.readTree(server.requests.getLast().body());
    }

    @Test
    @DisplayName("[AI-01] 번역은 Haiku 5.5 를 effort low·스트리밍으로 부르고, 문서는 document 블록, 지시는 시스템 프롬프트에만 둔다")
    void translationRequestShape() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 120, 42, translationChunks("Hello world"))));

        DocumentAi.Output<String> out = ai.translate(AiDocument.text("회의록.md", "안녕 세상\n둘째 줄"), TargetLanguage.JA, 64_000);

        assertThat(out.value()).isEqualTo("Hello world");
        assertThat(out.inputTokens()).isEqualTo(120);
        assertThat(out.outputTokens()).isEqualTo(42);
        JsonNode req = lastRequest();
        assertThat(server.requests.getLast().path()).isEqualTo("/v1/messages");
        assertThat(server.requests.getLast().apiKey()).isEqualTo("test-key");
        assertThat(req.path("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(req.path("stream").asBoolean()).isTrue();
        assertThat(req.path("max_tokens").asLong()).isEqualTo(64_000);
        assertThat(req.path("output_config").path("effort").asString()).isEqualTo("low");
        // 어려운 낱말을 notes 에서 먼저 정하고 translation 에 번역만 — 결과에 "Wait, …"가 섞이던 일 [AI-07]
        assertThat(req.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(req.path("output_config").path("format").path("schema").path("required").toString()).contains("notes").contains("translation");
        assertThat(req.has("temperature")).as("Haiku 5.5 는 기본값이 아닌 sampling 값을 거절").isFalse();
        assertThat(req.path("system").toString()).contains("Japanese").contains("untrusted");
        JsonNode doc = req.path("messages").get(0).path("content").get(0);
        assertThat(doc.path("type").asString()).isEqualTo("document");
        assertThat(doc.path("source").path("type").asString()).isEqualTo("text");
        assertThat(doc.path("source").path("data").asString()).isEqualTo("안녕 세상\n둘째 줄");
        // 번역에는 파일 이름을 보내지 않음 — 번역 첫 줄에 옮긴 파일 이름("週次会議メモ.txt")을 붙이는 일이 10번 중 1번 [AI-10]
        assertThat(doc.has("title")).isFalse();
    }

    @Test
    @DisplayName("[AI-01] 요약은 구조화 출력(JSON 스키마)으로 받아 요약 문단과 핵심 항목으로 나누고, PDF 는 원본을 base64 document 로 보낸다")
    void summaryWithStructuredOutputAndPdf() {
        server.reply(Reply.json(FakeAnthropicServer.message("end_turn",
                "{\"summary\":\"분기 실적을 정리한 문서입니다.\",\"keyPoints\":[\"매출 12% 증가\",\"비용 절감\"]}", 300, 50, null)));
        byte[] pdf = "%PDF-1.4 test".getBytes();

        DocumentAi.Output<DocumentAi.Summary> out = ai.summarize(AiDocument.pdf("보고서.pdf", pdf));

        assertThat(out.value().summary()).isEqualTo("분기 실적을 정리한 문서입니다.");
        assertThat(out.value().keyPoints()).containsExactly("매출 12% 증가", "비용 절감");
        assertThat(out.totalTokens()).isEqualTo(350);
        JsonNode req = lastRequest();
        assertThat(req.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(req.path("output_config").path("format").path("schema").path("required").toString()).contains("summary", "keyPoints");
        JsonNode doc = req.path("messages").get(0).path("content").get(0);
        assertThat(doc.path("source").path("type").asString()).isEqualTo("base64");
        assertThat(doc.path("source").path("media_type").asString()).isEqualTo("application/pdf");
        assertThat(doc.path("source").path("data").asString()).isEqualTo(Base64.getEncoder().encodeToString(pdf));
    }

    @Test
    @DisplayName("[AI-03] 입력 토큰은 무료 토큰 세기 API 로 같은 시스템 프롬프트·문서를 넣어 센다")
    void countsTokens() {
        server.reply(Reply.json("{\"input_tokens\":1234}"));

        assertThat(ai.countTranslationTokens(AiDocument.text("a.txt", "본문"), TargetLanguage.EN)).isEqualTo(1234);
        assertThat(server.requests.getLast().path()).isEqualTo("/v1/messages/count_tokens");
        assertThat(lastRequest().path("system").toString()).contains("English");
    }

    @Test
    @DisplayName("[AI-04] 안전 기준에 따른 거절은 422 AI_REFUSED")
    void refusalIs422() {
        server.reply(Reply.json(FakeAnthropicServer.message("refusal", null, 100, 0,
                "{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"x\"}")));

        assertThatThrownBy(() -> ai.summarize(AiDocument.text("a.txt", "본문")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.AI_REFUSED));
    }

    @Test
    @DisplayName("[AI-04] 결과가 출력 한도에서 잘리면 잘린 번역을 돌려주지 않고 413")
    void truncatedTranslationIsRejected() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("max_tokens", 100, 64_000, "잘린 번역")));

        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("[AI-04] Anthropic 의 요청 한도(429)는 503 — 잠시 뒤 다시 시도할 수 있음, 그 밖의 API 오류는 502")
    void mapsApiErrors() {
        server.reply(Reply.error(429, "rate_limit_error", "slow down"));
        assertThatThrownBy(() -> ai.summarize(AiDocument.text("a.txt", "본문")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        server.reply(Reply.error(401, "authentication_error", "bad key"));
        assertThatThrownBy(() -> ai.summarize(AiDocument.text("a.txt", "본문")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
    }

    @Test
    @DisplayName("[AI-06] 번역이 API 의 문서 표기(<document>…<document_content>)째로 오면 내용만 돌려준다 — 실측에서 발견, 원문이 그 표기면 그대로")
    void unwrapsEchoedDocumentMarkup() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks(
                "<document index=\"1\" media_type=\"text/plain\"><source>README.txt</source><document_content>"
                        + "안녕하세요\n둘째 줄\n</document_content></document>"))));
        assertThat(ai.translate(AiDocument.text("README.txt", "Hello\nsecond line\n"), TargetLanguage.KO, 64_000).value())
                .isEqualTo("안녕하세요\n둘째 줄\n");
        assertThat(lastRequest().path("system").toString()).contains("tags");

        String markup = "<document><document_content>표기 예시</document_content></document>";
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks(markup))));
        assertThat(ai.translate(AiDocument.text("markup.txt", markup), TargetLanguage.KO, 64_000).value()).isEqualTo(markup);
    }

    @Test
    @DisplayName("[AI-07] effort 는 작업마다 설정을 따르고, 프롬프트에 최종 번역만(고쳐 쓴 흔적 금지)·날짜·숫자·이름은 문서 그대로를 적는다 — 실측에서 발견")
    void effortFromConfigAndStricterPrompts() {
        AiProperties medium = new AiProperties("test-key", "claude-haiku-5-5", "high", "medium", 300_000, DataSize.ofMegabytes(10), Duration.ofSeconds(10),
                Duration.ofSeconds(30), 8);
        ClaudeDocumentAi mediumAi = new ClaudeDocumentAi(medium, AnthropicOkHttpClient.builder()
                .apiKey("test-key").baseUrl(server.baseUrl()).maxRetries(0).build());

        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 10, 5, translationChunks("Hi"))));
        mediumAi.translate(AiDocument.text("a.txt", "안녕"), TargetLanguage.EN, 64_000);
        JsonNode translation = lastRequest();
        assertThat(translation.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(translation.path("system").toString()).contains("final translation").contains("draft")
                // 따옴표 안 폴더 이름("온보딩")을 파일 이름으로 둘지 망설이다 결과에 "Wait, …"를 썼음 — 규칙의 모호함을 없앰
                .contains("file names with an extension").contains("Never explain or revise");

        server.reply(Reply.json(FakeAnthropicServer.message("end_turn", "{\"summary\":\"요약\",\"keyPoints\":[]}", 10, 5, null)));
        mediumAi.summarize(AiDocument.text("a.txt", "본문"));
        JsonNode summary = lastRequest();
        assertThat(summary.path("output_config").path("effort").asString()).isEqualTo("high");
        // "연도를 붙이지 말라"고 적으면 오히려 연도를 꺼냈음(실측) — 그 낱말 없이 "문서 그대로"만 적음
        assertThat(summary.path("system").toString()).contains("exactly as the document writes them").doesNotContain("year");
        mediumAi.destroy();
    }

    @Test
    @DisplayName("[AI-08] 요약이 원문에 없는 연도를 날짜 앞에 붙이면 뗀다(원문에 있는 연도는 두고, 원문 글자를 모르는 PDF 는 그대로) — 실측 10번 중 4번, 프롬프트로 줄지 않음")
    void dropsInventedYears() {
        server.reply(Reply.json(FakeAnthropicServer.message("end_turn",
                "{\"summary\":\"2024년 9월 24일 열린 회의입니다. 2026년 1월 출시 예정입니다.\",\"keyPoints\":[\"2024년 9월 30일까지 공지\",\"인덱스 추가\"]}",
                100, 50, null)));
        DocumentAi.Summary summary = ai.summarize(AiDocument.text("회의록.md", "일시: 9월 24일\n출시는 2026년 1월\n공지는 9월 30일까지")).value();
        assertThat(summary.summary()).isEqualTo("9월 24일 열린 회의입니다. 2026년 1월 출시 예정입니다.");
        assertThat(summary.keyPoints()).containsExactly("9월 30일까지 공지", "인덱스 추가");

        server.reply(Reply.json(FakeAnthropicServer.message("end_turn", "{\"summary\":\"2024년 9월 보고서\",\"keyPoints\":[]}", 100, 50, null)));
        assertThat(ai.summarize(AiDocument.pdf("보고서.pdf", new byte[]{1, 2, 3})).value().summary()).isEqualTo("2024년 9월 보고서");
    }

    @Test
    @DisplayName("[AI-09] 번역 전체가 마크다운 코드 울타리(```)에 싸여 오면 벗긴다 — 실측에서 CSV 번역에 두 줄이 더해짐, 원문이 코드 블록이면 그대로")
    void unwrapsCodeFence() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("```csv\n項目,担当\nA,B\n```"))));
        assertThat(ai.translate(AiDocument.text("할일.csv", "항목,담당\nA,B\n"), TargetLanguage.JA, 64_000).value()).isEqualTo("項目,担当\nA,B");
        assertThat(lastRequest().path("system").toString()).contains("code fences");

        String fenced = "```\necho hi\n```";
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks(fenced))));
        assertThat(ai.translate(AiDocument.text("명령.md", fenced), TargetLanguage.EN, 64_000).value()).isEqualTo(fenced);
    }

    @Test
    @DisplayName("[AI-07] 번역 응답이 약속한 형식(JSON)이 아니거나 번역이 비어 있으면 502")
    void malformedTranslationIs502() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, "그냥 글")));
        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));

        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks(" "))));
        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
    }

    /**
     * [AI-12] 거의 한국어인 PDF(영문 낱말 몇 개)를 한국어로 번역하자, 문서 대신 용어 대응표 {"e-Friend Expert": "e-Friend Expert"} 만 와서
     * 그대로 번역 결과로 보였습니다(운영 사용자 신고, 같은 문서의 영어 번역은 정상). 문서가 JSON 이 아닌데 결과가 JSON 이거나, 원문을 아는
     * 문서(텍스트·Word)에서 결과가 원문보다 지나치게 짧으면 문서 일부만 옮긴 것으로 보고 보여 주지 않습니다(과금된 실패).
     */
    @Test
    @DisplayName("[AI-12] 번역이 문서 대신 용어표(JSON)이거나 원문보다 지나치게 짧으면 불완전한 번역으로 보고 502 — 운영에서 한국어 PDF 번역 결과가 용어 하나였음")
    void incompleteTranslationIsRejected() {
        AiDocument pdf = AiDocument.pdf("Expert_VC++.pdf", "%PDF-1.4 sample".getBytes(StandardCharsets.UTF_8));
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 12_000, 40, translationChunks("{\"e-Friend Expert\": \"e-Friend Expert\"}"))));
        assertThatThrownBy(() -> ai.translate(pdf, TargetLanguage.KO, 64_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));

        String longText = "회의 결과를 정리합니다. 다음 주까지 각 팀은 일정을 확정합니다.\n".repeat(20);
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 800, 20, translationChunks("Meeting results."))));
        assertThatThrownBy(() -> ai.translate(AiDocument.text("회의.txt", longText), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));

        // 문서 자체가 JSON 이면 JSON 번역은 정상, 짧은 문서의 짧은 번역도 정상
        String jsonDoc = "{\"title\": \"Hello\"}";
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 20, translationChunks("{\"title\": \"안녕하세요\"}"))));
        assertThat(ai.translate(AiDocument.text("a.json", jsonDoc), TargetLanguage.KO, 64_000).value()).isEqualTo("{\"title\": \"안녕하세요\"}");
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 20, translationChunks("Hi"))));
        assertThat(ai.translate(AiDocument.text("a.txt", "안녕하세요"), TargetLanguage.EN, 64_000).value()).isEqualTo("Hi");
    }

    @Test
    @DisplayName("[AI-12] 번역 프롬프트는 이미 대상 언어인 부분도 빠짐없이 옮겨 문서 전체를 쓰고, 번역 칸에 용어표·JSON 을 넣지 않게 한다")
    void translationPromptAsksForWholeDocument() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("안녕하세요"))));
        ai.translate(AiDocument.text("a.txt", "Hello"), TargetLanguage.KO, 64_000);
        String system = lastRequest().path("system").toString();
        assertThat(system).contains("whole document").contains("never drop").contains("glossary");
        assertThat(system).doesNotContain("keep it as it is");
    }

    @Test
    @DisplayName("[AI-11] 한국어 결과에서 한글 바로 뒤에 섞인 \"の\"는 \"의\"로 고친다 — 실측에서 PDF 요약 10번 중 4~5번 \"증가분の\", 다른 일본어 글은 그대로")
    void fixesKanaParticleAfterHangul() {
        server.reply(Reply.json(FakeAnthropicServer.message("end_turn",
                "{\"summary\":\"증가분の 대부분은 옛 버전입니다.\",\"keyPoints\":[\"팀별 사용량の 차이\",\"東京の 지사\"]}", 100, 50, null)));
        DocumentAi.Summary summary = ai.summarize(AiDocument.pdf("보고서.pdf", new byte[]{1})).value();
        assertThat(summary.summary()).isEqualTo("증가분의 대부분은 옛 버전입니다.");
        assertThat(summary.keyPoints()).containsExactly("팀별 사용량의 차이", "東京の 지사");

        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("팀の 저장 공간"))));
        assertThat(ai.translate(AiDocument.text("a.txt", "Team storage"), TargetLanguage.KO, 64_000).value()).isEqualTo("팀의 저장 공간");
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("チームの容量"))));
        assertThat(ai.translate(AiDocument.text("a.txt", "Team storage"), TargetLanguage.JA, 64_000).value()).isEqualTo("チームの容量");
    }

    @Test
    @DisplayName("[H-2] 응답을 받은 뒤의 실패(잘림·거절·형식 오류)는 과금된 토큰을 함께 알린다 — 하루 한도에서 돌려주지 않게. API 오류는 과금 전 실패")
    void failuresAfterResponseCarryBilledTokens() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("max_tokens", 100, 64_000, "잘린 번역")));
        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(e.totalTokens()).isEqualTo(64_100L);
                });

        server.reply(Reply.json(FakeAnthropicServer.message("refusal", null, 300, 5,
                "{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":null}")));
        assertThatThrownBy(() -> ai.summarize(AiDocument.text("a.txt", "본문")))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> assertThat(e.totalTokens()).isEqualTo(305L));

        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, "그냥 글")));
        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> assertThat(e.totalTokens()).isEqualTo(150L));

        server.reply(Reply.error(429, "rate_limit_error", "slow down"));
        assertThatThrownBy(() -> ai.summarize(AiDocument.text("a.txt", "본문")))
                .isNotInstanceOf(DocumentAi.BilledFailure.class);
    }

    @Test
    @DisplayName("[H-2] 번역은 바이트가 계속 오면 AI_TIMEOUT 보다 오래 걸려도 끝까지 받고, 오다 멈추면 끊되 과금된 것으로 본다 — 120초가 스트림 전체에 걸려 큰 번역이 끊겼음")
    void translationTimeoutCoversStalls() {
        AiProperties shortTimeout = new AiProperties("test-key", "claude-haiku-5-5", "low", "low", 300_000, DataSize.ofMegabytes(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 8);
        ClaudeDocumentAi slow = new ClaudeDocumentAi(shortTimeout, ClaudeDocumentAi.client(shortTimeout, server.baseUrl(), 0));

        server.reply(Reply.slowSse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("Hello")), 300));   // 10개 이벤트 약 3초
        assertThat(slow.translate(AiDocument.text("a.txt", "안녕"), TargetLanguage.EN, 64_000).value()).isEqualTo("Hello");

        server.reply(Reply.slowSse(FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("Hello")), 2_000));   // 첫 이벤트 뒤 2초 멈춤
        assertThatThrownBy(() -> slow.translate(AiDocument.text("a.txt", "안녕"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> assertThat(e.totalTokens()).isNull());
        slow.destroy();
    }

    /** 운영과 같은 재시도 설정의 인스턴스(SDK 재시도 끔 + 앱이 3번까지, 대기만 짧게) */
    private ClaudeDocumentAi productionLike(AiProperties props) {
        return new ClaudeDocumentAi(props, ClaudeDocumentAi.client(props, server.baseUrl(), 0),
                new ClaudeDocumentAi.Retry(ClaudeDocumentAi.Retry.DEFAULT.attempts(), Duration.ofMillis(10)));
    }

    @Test
    @DisplayName("[H-2] 요약 응답이 시간 상한을 넘으면 다시 보내지 않고, 과금됐을 수 있는 실패로 본다 — 예전에는 시간 초과도 두 번 더 보내(최대 3번 과금) 예약을 전부 돌려줌")
    void summaryTimeoutIsNotRetriedAndCountsAsBilled() {
        AiProperties shortTimeout = new AiProperties("test-key", "claude-haiku-5-5", "low", "low", 300_000, DataSize.ofMegabytes(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 8);
        ClaudeDocumentAi slow = productionLike(shortTimeout);
        String reply = FakeAnthropicServer.message("end_turn", "{\"summary\":\"요약\",\"keyPoints\":[]}", 100, 50, null);
        for (int i = 0; i < 3; i++) {
            server.reply(Reply.delayedJson(reply, 2_000));
        }

        assertThatThrownBy(() -> slow.summarize(AiDocument.text("a.txt", "본문")))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> assertThat(e.totalTokens()).isNull());
        assertThat(server.requests).hasSize(1);
        slow.destroy();
    }

    @Test
    @DisplayName("[H-2] 과금 전 실패(요청 한도 429)는 다시 보내 성공할 수 있다 — 재시도를 앱이 맡아도 그대로(동작 고정)")
    void retriesRateLimitBeforeBilling() {
        ClaudeDocumentAi retrying = productionLike(new AiProperties("test-key", "claude-haiku-5-5", "low", "low", 300_000,
                DataSize.ofMegabytes(10), Duration.ofSeconds(10), Duration.ofSeconds(30), 8));
        server.reply(Reply.error(429, "rate_limit_error", "slow down"));
        server.reply(Reply.json(FakeAnthropicServer.message("end_turn", "{\"summary\":\"요약\",\"keyPoints\":[]}", 100, 50, null)));

        assertThat(retrying.summarize(AiDocument.text("a.txt", "본문")).value().summary()).isEqualTo("요약");
        assertThat(server.requests).hasSize(2);
        retrying.destroy();
    }

    @Test
    @DisplayName("[H-2] 연결 단계의 시간 초과는 요청이 서버에 닿지 않았으므로 과금 실패로 보지 않는다(다시 시도·예약 반환), 응답을 기다리다 난 시간 초과만 과금됐을 수 있음 — 독립 검토에서 발견")
    void connectTimeoutIsNotBillable() {
        assertThat(ClaudeDocumentAi.mayHaveBeenProcessed(new RuntimeException(new java.net.SocketTimeoutException("Connect timed out")))).isFalse();
        assertThat(ClaudeDocumentAi.mayHaveBeenProcessed(new RuntimeException(new java.net.SocketTimeoutException("Read timed out")))).isTrue();
        assertThat(ClaudeDocumentAi.mayHaveBeenProcessed(new RuntimeException(new java.io.InterruptedIOException("timeout")))).isTrue();
        assertThat(ClaudeDocumentAi.mayHaveBeenProcessed(new RuntimeException(new java.net.ConnectException("Connection refused")))).isFalse();
    }

    @Test
    @DisplayName("[H-2] 번역 스트림이 시작된 뒤 끝 표시(message_stop) 없이 닫혀도 과금된 실패로 본다 — 예전에는 500 이 나고 예약을 전부 돌려줌(독립 검토에서 발견)")
    void streamClosedWithoutStopIsBilled() {
        String full = FakeAnthropicServer.stream("end_turn", 100, 50, translationChunks("Hello"));
        String truncated = full.substring(0, full.indexOf("event: message_delta"));
        server.reply(Reply.sse(truncated));

        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "안녕"), TargetLanguage.EN, 64_000))
                .isInstanceOfSatisfying(DocumentAi.BilledFailure.class, e -> assertThat(e.totalTokens()).isNull());
    }
}
