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
        AiProperties props = new AiProperties("test-key", "claude-haiku-5-5", 300_000, DataSize.ofMegabytes(10), Duration.ofSeconds(10));
        ai = new ClaudeDocumentAi(props, AnthropicOkHttpClient.builder()
                .apiKey("test-key").baseUrl(server.baseUrl()).maxRetries(0).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private JsonNode lastRequest() {
        return json.readTree(server.requests.getLast().body());
    }

    @Test
    @DisplayName("[AI-01] 번역은 Haiku 5.5 를 effort low·스트리밍으로 부르고, 문서는 document 블록, 지시는 시스템 프롬프트에만 둔다")
    void translationRequestShape() {
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 120, 42, "Hello ", "world")));

        DocumentAi.Output<String> out = ai.translate(AiDocument.text("회의록.md", "안녕 세상\n둘째 줄"), TargetLanguage.JA);

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
        assertThat(req.has("temperature")).as("Haiku 5.5 는 기본값이 아닌 sampling 값을 거절").isFalse();
        assertThat(req.path("system").toString()).contains("Japanese").contains("untrusted");
        JsonNode doc = req.path("messages").get(0).path("content").get(0);
        assertThat(doc.path("type").asString()).isEqualTo("document");
        assertThat(doc.path("source").path("type").asString()).isEqualTo("text");
        assertThat(doc.path("source").path("data").asString()).isEqualTo("안녕 세상\n둘째 줄");
        assertThat(doc.path("title").asString()).isEqualTo("회의록.md");
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

        assertThatThrownBy(() -> ai.translate(AiDocument.text("a.txt", "본문"), TargetLanguage.EN))
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
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50,
                "<document index=\"1\" media_type=\"text/plain\"><source>README.txt</source><document_content>",
                "안녕하세요\n둘째 줄\n", "</document_content></document>")));
        assertThat(ai.translate(AiDocument.text("README.txt", "Hello\nsecond line\n"), TargetLanguage.KO).value())
                .isEqualTo("안녕하세요\n둘째 줄\n");
        assertThat(lastRequest().path("system").toString()).contains("tags");

        String markup = "<document><document_content>표기 예시</document_content></document>";
        server.reply(Reply.sse(FakeAnthropicServer.stream("end_turn", 100, 50, markup)));
        assertThat(ai.translate(AiDocument.text("markup.txt", markup), TargetLanguage.KO).value()).isEqualTo(markup);
    }
}
