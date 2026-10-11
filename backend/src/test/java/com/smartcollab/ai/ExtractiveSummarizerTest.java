package com.smartcollab.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

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

    @Test
    @DisplayName("[H-1] 동작 고정 — 메모리를 줄이도록 고쳐도 같은 문서에서 같은 문장을 같은 순서로 고른다(문장 40개·5,000개)")
    void sameSelectionAsBefore() {
        assertThat(selected(fixture(40, 42))).containsExactly(9, 14, 19, 32, 39);
        assertThat(selected(fixture(5000, 7))).containsExactly(321, 445, 2300, 2310, 4669);
        assertThat(summarizer.summarize(fixture(5000, 7)).totalSentences()).isEqualTo(5000);
    }

    /** 고른 문장의 원문 위치 */
    private List<Integer> selected(String text) {
        List<String> all = ExtractiveSummarizer.splitSentences(text);
        return summarizer.summarize(text).sentences().stream().map(all::indexOf).toList();
    }

    /** 단어를 무작위로 이어 붙인 문서 — 같은 씨앗이면 늘 같은 문서. 일곱 번째마다 줄바꿈, 가끔 조사 "를" */
    private static String fixture(int count, long seed) {
        String[] words = {"문서", "협업", "버전", "권한", "팀", "공유", "링크", "서명", "휴지통", "폴더", "검색", "알림", "채팅", "저장", "용량",
                "document", "team", "share", "version", "folder", "the", "and", "이", "그리고", "회의", "일정", "결정", "검토", "보안", "백업"};
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int n = 3 + r.nextInt(12);
            for (int j = 0; j < n; j++) sb.append(words[r.nextInt(words.length)]).append(r.nextInt(4) == 0 ? "를 " : " ");
            sb.append(i % 7 == 6 ? "\n" : ". ");
        }
        return sb.toString();
    }
}
