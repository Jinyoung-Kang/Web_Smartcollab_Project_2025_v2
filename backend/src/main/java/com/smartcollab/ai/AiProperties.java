package com.smartcollab.ai;

import com.smartcollab.global.config.AppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 문서 요약·번역(Claude) 설정 — application.yml 의 app.ai.* [AI-01, ADR-0011]
 *
 * @param apiKey              ANTHROPIC_API_KEY. 비어 있으면 생성형 요약·Claude 번역을 쓰지 않습니다
 * @param model               모델 ID (기본 claude-haiku-5-5)
 * @param summaryEffort       요약의 생각 양(low·medium·high·xhigh·max) [AI-07]
 * @param translationEffort   번역의 생각 양 [AI-07]
 * @param tokensPerUserPerDay 한 사용자가 24시간 동안 쓸 수 있는 토큰(입력+출력) [AI-03]
 * @param maxDocumentSize     PDF·Word 문서 크기 상한 [AI-02]
 * @param timeout             요청 하나의 시간 상한
 * @param translationTimeout  번역(스트리밍) 하나의 시간 상한 [H-2]
 * @param maxConcurrent       서버 전체에서 동시에 돌리는 요약·번역 수 [M-1]
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(String apiKey, String model, String summaryEffort, String translationEffort, long tokensPerUserPerDay, DataSize maxDocumentSize, Duration timeout,
                          Duration translationTimeout, int maxConcurrent) {

    public boolean enabled() {
        return StringUtils.hasText(apiKey);
    }

    /** API 키를 가립니다 [L-4] */
    @Override
    public String toString() {
        return "AiProperties[apiKey=" + AppProperties.redact(apiKey) + ", model=" + model + ", summaryEffort=" + summaryEffort
                + ", translationEffort=" + translationEffort + ", tokensPerUserPerDay=" + tokensPerUserPerDay
                + ", maxDocumentSize=" + maxDocumentSize + ", timeout=" + timeout + ", translationTimeout=" + translationTimeout
                + ", maxConcurrent=" + maxConcurrent + "]";
    }
}
