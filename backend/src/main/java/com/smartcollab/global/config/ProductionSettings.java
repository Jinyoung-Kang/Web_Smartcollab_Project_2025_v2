package com.smartcollab.global.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 운영(prod) 프로필에서 반드시 환경 변수로 받아야 하는 DB 접속 정보를 기동 초기(빈 생성 전)에 확인합니다 [S-14].
 * <p>공통 설정(application.yml)의 DB 기본값(localhost·smartcollab)은 개발용입니다. 운영 프로필은 이 기본값을 비우고,
 * 비어 있으면 DB 에 접속하기 전에 무엇이 빠졌는지 알려 주며 기동을 멈춥니다. JWT 비밀값은 {@code JwtTokenService} 가
 * 같은 방식으로 확인합니다. {@code META-INF/spring.factories} 에 등록되어 있습니다.</p>
 */
public class ProductionSettings implements EnvironmentPostProcessor {

    /** 실제 설정 키 → 안내할 환경 변수 이름. SPRING_DATASOURCE_* 로 직접 줘도 통과합니다. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        List<String> missing = REQUIRED.entrySet().stream()
                .filter(e -> !StringUtils.hasText(environment.getProperty(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("운영(prod) 프로필에서는 " + String.join(", ", missing) + " 환경 변수가 필수입니다.");
        }
    }
}
