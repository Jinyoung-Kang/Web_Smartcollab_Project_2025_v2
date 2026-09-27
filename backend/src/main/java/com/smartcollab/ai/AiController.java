package com.smartcollab.ai;

import com.smartcollab.file.FileContentService;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Document tools", description = "문서 요약(추출형)·번역(DeepL)")
@RestController
@RequiredArgsConstructor
public class AiController {

    private final FileContentService contentService;
    private final ExtractiveSummarizer summarizer;
    private final TranslationService translationService;
    private final AppProperties props;

    @Operation(summary = "핵심 문장 추출 요약", description = "저장된 현재 버전에서 단어 빈도 기반으로 중요한 문장을 고릅니다 (생성형 AI 아님).")
    @PostMapping("/api/files/{fileId}/summary")
    public SummaryResponse summarize(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        String text = contentService.readUtf8Content(fileId, user.id(), props.files().textEditMaxBytes());
        ExtractiveSummarizer.Summary summary = summarizer.summarize(text);
        return new SummaryResponse(summary.sentences(), summary.totalSentences(), "extractive-term-frequency");
    }

    @Operation(summary = "번역", description = "target = EN 또는 KO. DEEPL_API_KEY 가 없으면 503, 하루 번역 분량을 넘으면 429.")
    @PostMapping("/api/files/{fileId}/translation")
    public TranslationResponse translate(@PathVariable Long fileId, @RequestParam String target,
                                         @CurrentUser AuthUser user) {
        return translationService.translate(fileId, user.id(), target);
    }

    public record SummaryResponse(List<String> sentences, int totalSentences, String method) {
    }

    public record TranslationResponse(String text, String targetLang, String detectedSourceLang) {
    }
}
