package com.smartcollab.global.config;

import com.smartcollab.ai.AiProperties;
import com.smartcollab.global.gate.GateProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [L-4] 비밀값이 든 설정 record 의 기본 toString 은 모든 값을 그대로 적어, 설정 객체가 로그·예외 메시지에 찍히면 비밀값이 함께 남습니다.
 */
class SecretPropertiesTest {

    private static final String JWT = "jwt-secret-marker-0001";
    private static final String STORAGE = "AccountKey=storage-key-marker-0002";
    private static final String DEEPL = "deepl-key-marker-0003";
    private static final String DEMO = "demo-password-marker-0004";
    private static final String AI = "ai-key-marker-0005";
    private static final String GATE_SECRET = "gate-secret-marker-0006";
    private static final String GATE_CODES = "recruit:scrypt-hash-marker-0007";

    @Test
    @DisplayName("[L-4] 앱 설정의 toString 은 JWT 비밀 키·저장소 연결 문자열·DeepL 키·체험 계정 비밀번호를 적지 않는다")
    void appPropertiesHideSecrets() {
        AppProperties props = new AppProperties(new AppProperties.Jwt(JWT, Duration.ofHours(8)), new AppProperties.Cookie(true),
                new AppProperties.Cors(List.of()), new AppProperties.Storage("azure", "./data", STORAGE, "files"), null, null, null,
                new AppProperties.Deepl(DEEPL, "https://api-free.deepl.com"),
                new AppProperties.Demo(true, DEMO, DataSize.ofMegabytes(50), true), null);

        assertThat(props.toString()).doesNotContain(JWT, STORAGE, "storage-key-marker", DEEPL, DEMO)
                .contains("azure", "files", "PT8H");
    }

    @Test
    @DisplayName("[L-4] AI·접근 코드 설정의 toString 은 API 키·쿠키 서명 키·코드 해시를 적지 않는다")
    void aiAndGatePropertiesHideSecrets() {
        AiProperties ai = new AiProperties(AI, "claude-haiku-5-5", "low", "low", 1000, DataSize.ofMegabytes(10),
                Duration.ofSeconds(60), Duration.ofSeconds(120), 2);
        GateProperties gate = new GateProperties(GATE_CODES, GATE_SECRET, "상시", null);

        assertThat(ai.toString()).doesNotContain(AI).contains("claude-haiku-5-5");
        assertThat(gate.toString()).doesNotContain(GATE_SECRET, GATE_CODES, "scrypt-hash-marker").contains("상시");
    }
}
