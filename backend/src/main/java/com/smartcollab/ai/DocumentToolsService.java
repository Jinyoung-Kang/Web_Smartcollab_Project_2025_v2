package com.smartcollab.ai;

import com.smartcollab.file.DocumentKind;
import com.smartcollab.file.FileContentService;
import com.smartcollab.file.FileContentService.DocumentRef;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 문서 요약·번역 [AI-01, ADR-0011]. AI 키(ANTHROPIC_API_KEY)가 있으면 Claude 로, 없으면 추출 요약·DeepL(텍스트 번역)로 동작합니다.
 * <ol>
 *   <li>권한·형식·크기를 가장 먼저 봅니다 — 볼 수 없는 파일은 AI 설정과 상관없이 404 이고 AI 를 부르지 않습니다.</li>
 *   <li>같은 버전의 같은 요청은 저장해 둔 결과를 돌려줍니다(다시 과금하지 않음) [AI-05].</li>
 *   <li>호출 전에 입력 토큰을 세어 상한을 넘으면 413, 사용자별 하루 토큰 한도에 "입력 + 출력 상한"(요청의 max_tokens 와 같은 최악값)을
 *       예약해 넘으면 429 [AI-03, H-2]. 동시에 보낸 요청도 한도를 넘지 못합니다. 과금 전 실패(API 오류·연결 실패)는 예약을 돌려주고,
 *       성공과 응답을 받은 뒤의 실패(잘림·거절·형식 오류·스트림 끊김)는 실제 사용량(모르면 예약분 전부)으로 정산합니다.</li>
 * </ol>
 * 결과 캐시를 놓친 요청은 남은 한도를 먼저 보고, 사용자당·서버 전체 동시 실행 상한 안에서만 문서를 읽고 부릅니다 [M-1].
 * 호출마다 한 줄(작업·파일·버전·모델·토큰·걸린 시간)을 남깁니다. 문서 내용과 결과는 남기지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentToolsService {

    /** 요약 입력 상한. Haiku 5.5 는 입력이 10만 토큰을 넘는 요청에 입력·출력 모두 5배 단가를 매기므로 그 안으로 둡니다 */
    static final long SUMMARY_INPUT_CAP = 100_000;
    /** 번역 입력 상한. 번역은 입력과 비슷하거나 더 긴 출력을 내므로 출력 한도(64,000)의 절반 */
    static final long TRANSLATION_INPUT_CAP = 32_000;
    static final String GENERATIVE = "generative";
    static final String EXTRACTIVE = "extractive-term-frequency";
    private static final Duration DAY = Duration.ofDays(1);

    private final FileContentService contentService;
    private final DocumentAi ai;
    private final ExtractiveSummarizer summarizer;
    private final TranslationService deepl;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AiProperties props;
    private final AppProperties appProps;
    private final DocumentToolSlots slots;
    private final Map<String, SummaryResult> summaries = lru(200);
    private final Map<String, TranslationResult> translations = lru(50);

    public SummaryResult summarize(Long fileId, Long userId) {
        DocumentRef ref = contentService.resolveDocument(fileId, userId, props.maxDocumentSize().toBytes());
        if (!ai.enabled()) {
            if (ref.kind() == DocumentKind.PDF) {
                throw new ApiException(ErrorCode.FEATURE_DISABLED, "PDF 요약은 AI API 키(ANTHROPIC_API_KEY)가 설정되어 있어야 합니다.");
            }
            try (DocumentToolSlots.Slot ignored = slots.acquire(userId)) {
                ExtractiveSummarizer.Summary s = summarizer.summarize(text(ref, contentService.loadDocument(ref)));
                return new SummaryResult(EXTRACTIVE, null, null, s.sentences(), s.totalSentences());
            }
        }
        String key = ref.versionId() + ":" + ai.model();
        SummaryResult cached = summaries.get(key);
        if (cached != null) {
            return cached;
        }
        requireBudgetLeft(userId, DocumentAi.SUMMARY_MAX_OUTPUT_TOKENS);
        try (DocumentToolSlots.Slot ignored = slots.acquire(userId)) {
            AiDocument doc = aiDocument(ref);
            long input = ai.countSummaryTokens(doc);
            requireWithinCap(input, SUMMARY_INPUT_CAP, "요약");
            DocumentAi.Output<DocumentAi.Summary> out = withBudget("summary", fileId, userId, ref, input,
                    DocumentAi.SUMMARY_MAX_OUTPUT_TOKENS, () -> ai.summarize(doc));
            SummaryResult result = new SummaryResult(GENERATIVE, ai.model(), out.value().summary(), out.value().keyPoints(), null);
            summaries.put(key, result);
            return result;
        }
    }

    public TranslationResult translate(Long fileId, Long userId, String target) {
        TargetLanguage lang = TargetLanguage.parse(target);
        DocumentRef ref = contentService.resolveDocument(fileId, userId, props.maxDocumentSize().toBytes());
        if (!ai.enabled()) {
            if (ref.kind() != DocumentKind.TEXT) {
                throw new ApiException(ErrorCode.FEATURE_DISABLED, "PDF·Word 번역은 AI API 키(ANTHROPIC_API_KEY)가 설정되어 있어야 합니다.");
            }
            if (!deepl.enabled()) {
                throw TranslationService.disabled();
            }
            // DeepL 결과도 버전·언어별로 둡니다 — 예전에는 다시 요청할 때마다 DeepL 월 사용량과 하루 글자 한도를 씀 [L-4]
            String key = ref.versionId() + ":" + lang + ":deepl";
            TranslationResult cached = translations.get(key);
            if (cached != null) {
                return cached;
            }
            try (DocumentToolSlots.Slot ignored = slots.acquire(userId)) {
                TranslationService.TranslationResult t = deepl.translate(text(ref, contentService.loadDocument(ref)), userId, lang);
                TranslationResult result = new TranslationResult(t.text(), lang.name(), t.detectedSourceLanguage(), "deepl", null);
                translations.put(key, result);
                return result;
            }
        }
        String key = ref.versionId() + ":" + lang + ":" + ai.model();
        TranslationResult cached = translations.get(key);
        if (cached != null) {
            return cached;
        }
        requireBudgetLeft(userId, DocumentAi.translationMaxOutputTokens(0));
        try (DocumentToolSlots.Slot ignored = slots.acquire(userId)) {
            AiDocument doc = aiDocument(ref);
            long input = ai.countTranslationTokens(doc, lang);
            requireWithinCap(input, TRANSLATION_INPUT_CAP, "번역");
            long maxOutput = DocumentAi.translationMaxOutputTokens(input);
            DocumentAi.Output<String> out = withBudget("translate-" + lang, fileId, userId, ref, input, maxOutput,
                    () -> ai.translate(doc, lang, maxOutput));
            TranslationResult result = new TranslationResult(out.value(), lang.name(), null, "claude", ai.model());
            translations.put(key, result);
            return result;
        }
    }

    /**
     * 문서를 읽고 토큰을 세기 전에, 남은 하루 한도가 가장 작은 예약(출력 상한)에도 못 미치면 바로 429 [M-1].
     * 예전에는 한도를 다 쓴 사용자도 요청마다 문서 전체를 읽고 토큰 세기 API 를 부른 뒤에야 거절됐습니다.
     */
    private void requireBudgetLeft(Long userId, long minimumReserve) {
        if (rateLimiter.used(budgetKey(userId), DAY) + minimumReserve > props.tokensPerUserPerDay()) {
            throw budgetExceeded();
        }
    }

    private static String budgetKey(Long userId) {
        return "ai-tokens:" + userId;
    }

    private ApiException budgetExceeded() {
        return new ApiException(ErrorCode.RATE_LIMITED, "하루 AI 사용량(" + NumberFormat.getIntegerInstance(Locale.KOREA)
                .format(props.tokensPerUserPerDay()) + " 토큰)을 넘었습니다. 24시간 안에 쓴 양이 줄어들면 다시 사용할 수 있습니다.");
    }

    private AiDocument aiDocument(DocumentRef ref) {
        byte[] bytes = contentService.loadDocument(ref);
        if (ref.kind() == DocumentKind.PDF) {
            return AiDocument.pdf(ref.name(), bytes);
        }
        String text = text(ref, bytes);
        if (text.isBlank()) {
            throw ApiException.badRequest("내용이 없는 문서는 요약·번역할 수 없습니다.");
        }
        return AiDocument.text(ref.name(), text);
    }

    /**
     * 문서 본문. 텍스트 문서는 편집기 한도(바이트)로 이미 막히고, Word 는 뽑은 본문에 같은 값을 글자 수 상한으로 둡니다 —
     * 상한이 없어 추출 요약이 2,100만 자를 받던 일이 있었습니다 [H-1].
     */
    private String text(DocumentRef ref, byte[] bytes) {
        return ref.kind() == DocumentKind.DOCX
                ? DocxText.extract(bytes, (int) Math.min(Integer.MAX_VALUE, appProps.files().textEditMaxBytes()))
                : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireWithinCap(long inputTokens, long cap, String task) {
        if (inputTokens > cap) {
            NumberFormat n = NumberFormat.getIntegerInstance(Locale.KOREA);
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "문서가 너무 깁니다(약 " + n.format(inputTokens) + " 토큰). "
                    + task + "은 " + n.format(cap) + " 토큰 이하 문서만 가능합니다. 문서를 나눠서 시도하세요.");
        }
    }

    /** 하루 토큰 한도에 예약 → 호출 → 정산(과금 전 실패면 예약 취소, 과금 뒤 실패면 쓴 만큼) */
    private <T> DocumentAi.Output<T> withBudget(String task, Long fileId, Long userId, DocumentRef ref, long input,
                                                long outputReserve, Supplier<DocumentAi.Output<T>> call) {
        String budgetKey = budgetKey(userId);
        long reserve = input + outputReserve;
        if (!rateLimiter.tryAcquire(budgetKey, reserve, props.tokensPerUserPerDay(), DAY)) {
            throw budgetExceeded();
        }
        long started = System.nanoTime();
        DocumentAi.Output<T> out;
        try {
            out = call.get();
        } catch (DocumentAi.BilledFailure e) {
            // 응답을 받은 뒤의 실패는 이미 과금됨 — 아는 사용량을, 모르면 예약분 전부를 남깁니다 [H-2]
            long used = e.totalTokens() == null ? reserve : e.totalTokens();
            rateLimiter.release(budgetKey, reserve);
            rateLimiter.charge(budgetKey, used, DAY);
            log.info("AI {} failed after billing file={} version={} code={} tokens={}", task, fileId, ref.versionId(), e.code(), used);
            throw e;
        } catch (RuntimeException e) {
            rateLimiter.release(budgetKey, reserve);
            throw e;
        }
        rateLimiter.release(budgetKey, reserve);
        rateLimiter.charge(budgetKey, out.totalTokens(), DAY);
        log.info("AI {} file={} version={} kind={} model={} tokens in={} out={} {}ms", task, fileId, ref.versionId(), ref.kind(),
                ai.model(), out.inputTokens(), out.outputTokens(), (System.nanoTime() - started) / 1_000_000);
        return out;
    }

    /** 최근에 쓴 것부터 남기는 작은 메모리 캐시. 서버를 다시 켜면 비워집니다 */
    private static <V> Map<String, V> lru(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > capacity;
            }
        });
    }

    /**
     * @param model          생성형일 때 쓴 모델(추출형이면 null)
     * @param summary        생성형 요약 문단(추출형이면 null)
     * @param sentences      생성형이면 핵심 항목, 추출형이면 고른 원문 문장
     * @param totalSentences 추출 요약일 때 원문 문장 수, 생성형이면 null
     */
    public record SummaryResult(String method, String model, String summary, List<String> sentences, Integer totalSentences) {
    }

    /** @param provider claude 또는 deepl */
    public record TranslationResult(String text, String targetLanguage, String detectedSourceLanguage, String provider, String model) {
    }
}
