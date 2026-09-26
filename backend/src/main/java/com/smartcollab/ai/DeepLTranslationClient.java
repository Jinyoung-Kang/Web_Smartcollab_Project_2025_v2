package com.smartcollab.ai;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepL 번역 API 클라이언트.
 * <ul>
 *   <li>인증: {@code Authorization: DeepL-Auth-Key} 헤더 (v1 은 폐기 예정인 auth_key 폼 파라미터 사용)</li>
 *   <li>키가 없으면 번역을 흉내 내지 않고 "설정되지 않음"(503)을 반환합니다. v1 은 "[MOCK]" 문자열을 번역 결과처럼 보여 줬습니다.</li>
 *   <li>긴 문서는 줄 단위로 나눠 여러 text 항목으로 한 번에 보내고, 응답을 줄바꿈으로 이어 붙여 서식을 보존합니다.</li>
 * </ul>
 */
@Slf4j
@Component
public class DeepLTranslationClient {

    static final int CHUNK_CHARS = 4_000;
    public static final int MAX_CHARS = 50_000;

    private final AppProperties.Deepl config;
    private final RestClient client;

    public DeepLTranslationClient(AppProperties props, RestClient.Builder builder) {
        this.config = props.deepl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Translation translate(String text, String targetLang) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "번역 API 키(DEEPL_API_KEY)가 설정되지 않아 번역을 사용할 수 없습니다.");
        }
        if (text.length() > MAX_CHARS) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "번역은 " + MAX_CHARS + "자 이하 문서만 가능합니다.");
        }
        List<String> chunks = chunkByLines(text, CHUNK_CHARS);
        try {
            DeepLResponse res = client.post()
                    .uri("/v2/translate")
                    .header("Authorization", "DeepL-Auth-Key " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", chunks, "target_lang", targetLang, "preserve_formatting", true))
                    .retrieve()
                    .body(DeepLResponse.class);
            if (res == null || res.translations() == null || res.translations().size() != chunks.size()) {
                throw new ApiException(ErrorCode.UPSTREAM_ERROR, "번역 응답이 올바르지 않습니다.");
            }
            String joined = String.join("\n", res.translations().stream().map(DeepLResponse.Item::text).toList());
            return new Translation(joined, res.translations().getFirst().detectedSourceLanguage());
        } catch (RestClientException e) {
            log.warn("DeepL call failed: {}", e.getMessage());
            throw new ApiException(ErrorCode.UPSTREAM_ERROR, "번역 서비스 호출에 실패했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    /** 줄 경계를 지키며 maxChars 이하로 나눕니다. 한 줄이 너무 길면 그 줄만 강제로 자릅니다. */
    static List<String> chunkByLines(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.add(line.substring(0, maxChars));
                line = line.substring(maxChars);
            }
            if (!current.isEmpty() && current.length() + 1 + line.length() > maxChars) {
                chunks.add(current.toString());
                current.setLength(0);
            } else if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        chunks.add(current.toString());
        return chunks;
    }

    public record Translation(String text, String detectedSourceLanguage) {
    }

    record DeepLResponse(List<Item> translations) {
        record Item(@com.fasterxml.jackson.annotation.JsonProperty("detected_source_language") String detectedSourceLanguage,
                    String text) {
        }
    }
}
