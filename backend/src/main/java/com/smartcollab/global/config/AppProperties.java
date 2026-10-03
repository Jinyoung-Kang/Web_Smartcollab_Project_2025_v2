package com.smartcollab.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

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
        Quota quota,
        Deepl deepl,
        Demo demo,
        Http http
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

    /**
     * @param maxFolderDepth 최상위 아래 폴더 깊이 상한 [S-05]
     * @param maxCopyFolders 한 번에 복사할 수 있는 폴더 수 [S-04]
     */
    public record Files(long textEditMaxBytes, int trashRetentionDays, int maxFolderDepth, int maxCopyFolders) {
    }

    /** @param translationCharsPerUserPerDay 한 사용자가 하루에 번역할 수 있는 글자 수 (DeepL 월 사용량 보호) [SEC-07] */
    public record RateLimit(int loginPerMinute, int loginPerAccountPer10Minutes, int sharePasswordPer10Minutes,
                            int sharePasswordPerLinkPer10Minutes, int signupPerHour, long translationCharsPerUserPerDay) {
    }

    /**
     * 저장 공간 한도 (옛 버전·휴지통 포함 실제 저장량 기준)
     *
     * @param teamsPerUser 한 사람이 팀장인 팀 수 상한 — 팀마다 한도를 받으므로 팀 수도 제한합니다 [S-10]
     */
    public record Quota(DataSize personal, DataSize team, int teamsPerUser) {
    }

    public record Deepl(String apiKey, String baseUrl) {
        public boolean enabled() {
            return StringUtils.hasText(apiKey);
        }
    }

    /** @param quota 체험 계정의 개인 저장소와 체험 계정이 팀장인 팀에 적용하는 한도 */
    public record Demo(boolean enabled, String password, DataSize quota) {
    }

    /** @param maxBodySize 파일 업로드를 뺀 요청 본문(JSON 등)의 최대 크기 [SEC-10] */
    public record Http(DataSize maxBodySize) {
    }
}
