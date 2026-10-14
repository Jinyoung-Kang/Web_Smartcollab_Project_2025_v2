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
        @Override
        public String toString() {
            return "Jwt[secret=" + redact(secret) + ", ttl=" + ttl + "]";
        }
    }

    public record Cookie(boolean secure) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Storage(String type, String localRoot, String azureConnectionString, String azureContainer) {
        public boolean isAzure() {
            return "azure".equalsIgnoreCase(type);
        }

        @Override
        public String toString() {
            return "Storage[type=" + type + ", localRoot=" + localRoot + ", azureConnectionString=" + redact(azureConnectionString)
                    + ", azureContainer=" + azureContainer + "]";
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
     * @param teamsPerUser 한 계정이 속할 수 있는 팀 수(팀장·멤버 포함) [CAP-02]. 팀마다 저장 한도를 받으므로 팀장인 팀 수도 이 안에 듭니다 [S-10]
     * @param accounts     가입할 수 있는 계정 수(체험 계정 포함, 관리·시스템 계정 제외) [CAP-03]
     */
    public record Quota(DataSize personal, DataSize team, int teamsPerUser, int accounts) {
    }

    public record Deepl(String apiKey, String baseUrl) {
        public boolean enabled() {
            return StringUtils.hasText(apiKey);
        }

        @Override
        public String toString() {
            return "Deepl[apiKey=" + redact(apiKey) + ", baseUrl=" + baseUrl + "]";
        }
    }

    /**
     * @param quota       체험 계정의 개인 저장소와 체험 계정이 팀장인 팀에 적용하는 한도
     * @param sampleFiles 체험 계정마다 내 드라이브에 '파일 유형별 시험' 폴더(표본 16개, 약 190KB)를 만들지
     */
    public record Demo(boolean enabled, String password, DataSize quota, boolean sampleFiles) {
        @Override
        public String toString() {
            return "Demo[enabled=" + enabled + ", password=" + redact(password) + ", quota=" + quota + ", sampleFiles=" + sampleFiles + "]";
        }
    }

    /**
     * 비밀값을 가립니다 [L-4] — record 의 기본 toString 은 모든 값을 그대로 적어, 설정 객체가 로그·예외 메시지에 찍히면 비밀값이 함께 남습니다.
     * 설정됐는지는 알 수 있게 비었으면 빈 문자열, 있으면 별표로 적습니다.
     */
    public static String redact(String secret) {
        return StringUtils.hasText(secret) ? "****" : "";
    }

    /** @param maxBodySize 파일 업로드를 뺀 요청 본문(JSON 등)의 최대 크기 [SEC-10] */
    public record Http(DataSize maxBodySize) {
    }
}
