package com.smartcollab.ai;

import com.smartcollab.file.FileContentService;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SEC-07] 번역은 외부 서비스(DeepL)의 월 사용량을 쓰므로, 한 사용자가 다 써 버리지 못하게 하루 번역 분량(글자 수)을 제한합니다.
 */
class TranslationServiceTest {

    private static final long DAILY_CHARS = 100;

    private final FileContentService content = mock(FileContentService.class);
    private final DeepLTranslationClient deepl = mock(DeepLTranslationClient.class);
    private TranslationService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(null, null, null, null, new AppProperties.Files(2048, 30, 50, 1000),
                new AppProperties.RateLimit(10, 20, 10, 50, 5, DAILY_CHARS), null, null, null, null);
        service = new TranslationService(content, deepl, new SlidingWindowRateLimiter(), props);
        when(deepl.enabled()).thenReturn(true);
        when(deepl.translate(anyString(), anyString())).thenReturn(new DeepLTranslationClient.Translation("번역", "KO"));
    }

    @Test
    @DisplayName("하루 번역 분량을 넘으면 DeepL 을 부르지 않고 429")
    void rejectsOverDailyBudget() {
        when(content.readUtf8Content(eq(1L), eq(7L), anyLong())).thenReturn("가".repeat(60));

        assertThat(service.translate(1L, 7L, "EN").text()).isEqualTo("번역");
        assertThatThrownBy(() -> service.translate(1L, 7L, "EN"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(deepl, times(1)).translate(anyString(), anyString());
    }

    @Test
    @DisplayName("분량은 사용자마다 따로 센다")
    void budgetIsPerUser() {
        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenReturn("가".repeat(100));

        service.translate(1L, 7L, "EN");
        service.translate(1L, 8L, "EN");

        verify(deepl, times(2)).translate(anyString(), anyString());
    }

    @Test
    @DisplayName("번역을 쓸 수 없거나 문서가 너무 길면 분량을 쓰지 않는다")
    void doesNotSpendBudgetOnRejectedRequests() {
        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenReturn("가".repeat(100));
        when(deepl.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service.translate(1L, 7L, "EN"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.FEATURE_DISABLED));

        when(deepl.enabled()).thenReturn(true);
        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenReturn("가".repeat(DeepLTranslationClient.MAX_CHARS + 1));
        assertThatThrownBy(() -> service.translate(1L, 7L, "EN"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        verify(deepl, never()).translate(anyString(), anyString());

        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenReturn("가".repeat(100));
        assertThat(service.translate(1L, 7L, "EN").text()).isEqualTo("번역");   // 하루 분량 전부가 남아 있음
    }

    @Test
    @DisplayName("[S-16] DeepL 호출이 실패하면 그 요청의 분량을 돌려준다")
    void refundsBudgetWhenUpstreamFails() {
        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenReturn("가".repeat(100));
        when(deepl.translate(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_ERROR, "번역 서비스 호출에 실패했습니다."))
                .thenReturn(new DeepLTranslationClient.Translation("번역", "KO"));

        assertThatThrownBy(() -> service.translate(1L, 7L, "EN"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
        assertThat(service.translate(1L, 7L, "EN").text()).isEqualTo("번역");   // 이전: 실패한 호출이 하루 분량을 다 써서 429
    }

    @Test
    @DisplayName("볼 수 없는 파일이면 번역 설정과 상관없이 404 (설정 정보를 먼저 드러내지 않음)")
    void checksAccessBeforeFeatureAvailability() {
        when(deepl.enabled()).thenReturn(false);
        when(content.readUtf8Content(anyLong(), anyLong(), anyLong())).thenThrow(ApiException.notFound("파일"));

        assertThatThrownBy(() -> service.translate(1L, 7L, "EN"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("대상 언어는 EN·KO 만")
    void rejectsUnknownTarget() {
        assertThatThrownBy(() -> service.translate(1L, 7L, "JA"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
