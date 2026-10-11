package com.smartcollab.ai;

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

@Tag(name = "Document tools", description = "문서 요약·번역 — Claude(키가 있을 때), 없으면 추출 요약·DeepL")
@RestController
@RequiredArgsConstructor
public class AiController {

    private final DocumentToolsService tools;

    @Operation(summary = "문서 요약",
            description = "텍스트·PDF·Word(docx)의 저장된 현재 버전을 요약합니다. ANTHROPIC_API_KEY 가 있으면 Claude 로 생성형 요약(method=generative), "
                    + "없으면 단어 빈도로 원문 문장을 고르는 추출 요약(method=extractive-term-frequency, PDF 는 503). 하루 토큰 한도를 넘으면 429.")
    @PostMapping("/api/files/{fileId}/summary")
    public SummaryResponse summarize(@PathVariable Long fileId, @CurrentUser AuthUser user) {
        DocumentToolsService.SummaryResult r = tools.summarize(fileId, user.id());
        return new SummaryResponse(r.sentences(), r.totalSentences(), r.method(), r.summary(), r.model());
    }

    @Operation(summary = "번역", description = "target = EN·KO·JA·ZH. Claude(키가 있을 때) 또는 DeepL(텍스트 문서만). 둘 다 없으면 503, 하루 한도를 넘으면 429.")
    @PostMapping("/api/files/{fileId}/translation")
    public TranslationResponse translate(@PathVariable Long fileId, @RequestParam String target, @CurrentUser AuthUser user) {
        DocumentToolsService.TranslationResult r = tools.translate(fileId, user.id(), target);
        return new TranslationResponse(r.text(), r.targetLanguage(), r.detectedSourceLanguage(), r.provider(), r.model());
    }

    /**
     * @param sentences      생성형이면 핵심 항목, 추출형이면 고른 원문 문장
     * @param totalSentences 추출형일 때 원문 문장 수(생성형이면 null)
     * @param summary        생성형 요약 문단(추출형이면 null)
     */
    public record SummaryResponse(List<String> sentences, Integer totalSentences, String method, String summary, String model) {
    }

    public record TranslationResponse(String text, String targetLang, String detectedSourceLang, String provider, String model) {
    }
}
