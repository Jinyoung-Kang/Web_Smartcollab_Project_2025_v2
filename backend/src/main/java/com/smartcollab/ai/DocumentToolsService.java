package com.smartcollab.ai;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 문서 요약·번역 [AI-01].
 */
@Service
public class DocumentToolsService {

    public SummaryResult summarize(Long fileId, Long userId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public TranslationResult translate(Long fileId, Long userId, String target) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** @param totalSentences 추출 요약일 때 원문 문장 수, 생성형이면 null */
    public record SummaryResult(String method, String model, String summary, List<String> sentences, Integer totalSentences) {
    }

    /** @param provider claude 또는 deepl */
    public record TranslationResult(String text, String targetLanguage, String detectedSourceLanguage, String provider, String model) {
    }
}
