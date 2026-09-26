package com.smartcollab.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * application.yml 의 app.* 설정을 타입 안전하게 바인딩합니다.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Cookie cookie,
        Cors cors,
        Storage storage,
        Files files,
        RateLimit rateLimit,
        Deepl deepl,
        Demo demo
) {

    public record Jwt(String secret, Duration ttl) {
    }

    public record Cookie(boolean secure) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Storage(String type, String localRoot, String azureConnectionString, String azureContainer) {
        public boolean isAzure() {
            return "azure".equalsIgnoreCase(type);
        }
    }

    public record Files(long textEditMaxBytes, int trashRetentionDays) {
    }

    public record RateLimit(int loginPerMinute, int loginPerAccountPer10Minutes, int sharePasswordPer10Minutes,
                            int sharePasswordPerLinkPer10Minutes) {
    }

    public record Deepl(String apiKey, String baseUrl) {
        public boolean enabled() {
            return StringUtils.hasText(apiKey);
        }
    }

    public record Demo(boolean enabled, String password) {
    }
}
