package com.smartcollab.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractiveSummarizerTest {

    ExtractiveSummarizer summarizer = new ExtractiveSummarizer();

    @Test
    @DisplayName("주제어가 많이 등장하는 문장을 원문 순서대로 고른다")
    void picksKeySentences() {
        String text = """
                # 회의록
                이번 프로젝트의 목표는 문서 협업 시간을 줄이는 것이다.
                오늘 날씨가 맑았다.
                문서 협업 도구는 문서 버전과 문서 권한을 함께 관리해야 한다.
                커피를 마셨다.
                협업 문서의 변경 이력은 버전 기록으로 남긴다.
                점심은 늦게 먹었다.
                """;
        ExtractiveSummarizer.Summary s = summarizer.summarize(text);
        assertThat(s.totalSentences()).as("4자 미만 제목(# 회의록)은 문장에서 제외").isEqualTo(6);
        assertThat(s.sentences()).hasSize(2);
        assertThat(s.sentences()).allMatch(sentence -> sentence.contains("문서"));
        assertThat(String.join(" ", s.sentences())).doesNotContain("날씨").doesNotContain("커피");
    }

    @Test
    @DisplayName("한국어 조사를 떼어 같은 단어로 센다")
    void stripsParticles() {
        assertThat(ExtractiveSummarizer.stripKoreanSuffix("문서를")).isEqualTo("문서");
        assertThat(ExtractiveSummarizer.stripKoreanSuffix("협업에서")).isEqualTo("협업");
        assertThat(ExtractiveSummarizer.stripKoreanSuffix("english")).isEqualTo("english");
        assertThat(ExtractiveSummarizer.stripKoreanSuffix("이")).isEqualTo("이");
    }

    @Test
    @DisplayName("빈 문서는 빈 요약")
    void empty() {
        assertThat(summarizer.summarize("  ").sentences()).isEmpty();
    }
}
