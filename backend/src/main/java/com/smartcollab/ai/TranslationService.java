package com.smartcollab.ai;

import com.smartcollab.file.FileContentService;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 문서 번역. 외부 서비스(DeepL)의 월 사용량을 쓰므로 사용자마다 하루 번역 글자 수를 제한합니다 [SEC-07].
 * 권한 확인(문서 읽기)을 가장 먼저 합니다 — 볼 수 없는 파일이면 번역 설정과 상관없이 404 입니다.
 * 번역을 쓸 수 없거나 문서가 너무 길어 거절되는 요청은 분량을 쓰지 않고, 번역 서비스 호출이 실패하면 쓴 분량을 돌려줍니다 [S-16].
 */
@Service
@RequiredArgsConstructor
public class TranslationService {

    private static final Map<String, String> TARGETS = Map.of("EN", "EN-US", "KO", "KO");

    private final FileContentService contentService;
    private final DeepLTranslationClient translator;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AppProperties props;

    public TranslationResult translate(Long fileId, Long userId, String target) {
        String deeplTarget = TARGETS.get(target.toUpperCase(Locale.ROOT));
        if (deeplTarget == null) {
            throw ApiException.badRequest("target 은 EN 또는 KO 입니다.");
        }
        String text = contentService.readUtf8Content(fileId, userId, props.files().textEditMaxBytes());
        if (!translator.enabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "번역 API 키(DEEPL_API_KEY)가 설정되지 않아 번역을 사용할 수 없습니다.");
        }
        if (text.length() > DeepLTranslationClient.MAX_CHARS) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "번역은 " + DeepLTranslationClient.MAX_CHARS + "자 이하 문서만 가능합니다.");
        }
        long dailyChars = props.rateLimit().translationCharsPerUserPerDay();
        String budgetKey = "translate:" + userId;
        if (!rateLimiter.tryAcquire(budgetKey, text.length(), dailyChars, Duration.ofDays(1))) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "하루 번역 분량(" + NumberFormat.getIntegerInstance(Locale.KOREA).format(dailyChars)
                    + "자)을 넘었습니다. 24시간 안에 번역한 분량이 줄어들면 다시 번역할 수 있습니다.");
        }
        DeepLTranslationClient.Translation t;
        try {
            t = translator.translate(text, deeplTarget);
        } catch (RuntimeException e) {
            rateLimiter.release(budgetKey, text.length());
            throw e;
        }
        return new TranslationResult(t.text(), deeplTarget, t.detectedSourceLanguage());
    }

    /** 번역 결과. 응답 형식은 컨트롤러가 정합니다 [A-05]. */
    public record TranslationResult(String text, String targetLanguage, String detectedSourceLanguage) {
    }
}
