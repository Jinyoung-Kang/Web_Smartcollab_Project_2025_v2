package com.smartcollab.ai;

import java.util.List;

/**
 * 생성형 AI 로 문서를 요약·번역합니다. 구현은 {@link ClaudeDocumentAi}(Claude), 시험은 이 인터페이스를 가짜로 바꿉니다 [AI-01].
 */
public interface DocumentAi {

    /** API 키가 설정되어 쓸 수 있는지 */
    boolean enabled();

    /** 쓰는 모델 ID (응답·화면에 그대로 표시) */
    String model();

    /** 요약 요청의 입력 토큰 수(시스템 프롬프트 포함) — 호출 전에 한도를 확인하는 데 씁니다 [AI-03] */
    long countSummaryTokens(AiDocument document);

    /** 번역 요청의 입력 토큰 수(시스템 프롬프트 포함) [AI-03] */
    long countTranslationTokens(AiDocument document, TargetLanguage target);

    Output<Summary> summarize(AiDocument document);

    Output<String> translate(AiDocument document, TargetLanguage target);

    /** 결과와 실제로 쓴 토큰 — 하루 한도를 실제 사용량으로 정산합니다 [AI-03] */
    record Output<T>(T value, long inputTokens, long outputTokens) {
        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    record Summary(String summary, List<String> keyPoints) {
    }
}
