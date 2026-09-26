package com.smartcollab.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * 단어 빈도 기반 추출 요약 (외부 API·생성형 AI 사용 안 함).
 * <ol>
 *   <li>문장 분리: 문장부호(. ! ? 。) 뒤 공백 또는 줄바꿈 기준</li>
 *   <li>토큰화: 소문자화 후 글자·숫자 외 문자로 분리, 한국어는 흔한 조사·어미를 떼어 같은 단어로 묶음</li>
 *   <li>단어 가중치: 문서 내 빈도 / 최대 빈도 (불용어 제외)</li>
 *   <li>문장 점수: 문장에 포함된 서로 다른 단어 가중치의 합 / (1 + ln(문장 길이)) — 긴 문장이 유리해지는 것을 보정</li>
 *   <li>점수 상위 k 문장(전체의 30%, 1~5개)을 원래 순서대로 반환</li>
 * </ol>
 * v1 의 "요약"은 앞 150자를 자른 모의(Mock) 결과였습니다.
 */
@Component
public class ExtractiveSummarizer {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。])\\s+|\\R+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "for", "with", "is", "are", "was", "were",
            "be", "been", "it", "this", "that", "these", "those", "as", "at", "by", "from", "we", "you", "they", "he",
            "she", "i", "our", "your", "their", "will", "can", "not", "have", "has", "had", "do", "does", "did",
            "그리고", "그러나", "하지만", "또한", "및", "등", "이", "그", "저", "것", "수", "있다", "없다", "한다", "했다",
            "합니다", "했습니다", "있습니다", "입니다", "위해", "통해", "대한", "대해", "때문", "경우", "우리", "다음");
    private static final List<String> KOREAN_SUFFIXES = List.of(
            "으로부터", "에서부터", "으로써", "으로서", "에게서", "에서는", "에서", "에게", "으로", "까지", "부터",
            "보다", "처럼", "만큼", "이며", "이고", "하고", "와", "과", "은", "는", "이", "가", "을", "를", "의", "에",
            "로", "도", "만");

    public Summary summarize(String text) {
        List<String> sentences = splitSentences(text);
        if (sentences.isEmpty()) {
            return new Summary(List.of(), 0);
        }
        List<List<String>> tokens = sentences.stream().map(ExtractiveSummarizer::tokenize).toList();
        Map<String, Integer> freq = new HashMap<>();
        tokens.forEach(ts -> ts.forEach(t -> freq.merge(t, 1, Integer::sum)));
        int max = freq.values().stream().max(Integer::compareTo).orElse(1);

        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            List<String> ts = tokens.get(i);
            if (ts.isEmpty()) continue;
            double sum = new HashSet<>(ts).stream().mapToDouble(t -> (double) freq.get(t) / max).sum();
            scores[i] = sum / (1 + Math.log(ts.size()));
        }
        int k = Math.clamp(Math.round(sentences.size() * 0.3), 1, 5);
        List<Integer> top = IntStream.range(0, sentences.size()).boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed().thenComparing(i -> i))
                .limit(k)
                .sorted()
                .toList();
        return new Summary(top.stream().map(sentences::get).toList(), sentences.size());
    }

    static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : SENTENCE_SPLIT.split(text.strip())) {
            String s = raw.strip().replaceFirst("^[#>*\\-\\s]+", "");   // 마크다운 머리 기호 제거
            if (s.codePointCount(0, s.length()) >= 4) {
                out.add(s);
            }
        }
        return out;
    }

    static List<String> tokenize(String sentence) {
        List<String> out = new ArrayList<>();
        for (String raw : NON_WORD.split(sentence.toLowerCase(Locale.ROOT))) {
            String t = stripKoreanSuffix(raw);
            if (t.codePointCount(0, t.length()) >= 2 && !STOPWORDS.contains(t) && !STOPWORDS.contains(raw)) {
                out.add(t);
            }
        }
        return out;
    }

    static String stripKoreanSuffix(String word) {
        if (word.isEmpty() || !Character.UnicodeScript.of(word.codePointAt(0)).equals(Character.UnicodeScript.HANGUL)) {
            return word;
        }
        for (String suffix : KOREAN_SUFFIXES) {
            if (word.length() > suffix.length() + 1 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    public record Summary(List<String> sentences, int totalSentences) {
    }
}
