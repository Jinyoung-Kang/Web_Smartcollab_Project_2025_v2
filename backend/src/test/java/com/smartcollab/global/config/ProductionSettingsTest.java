package com.smartcollab.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [S-14] 운영(prod) 프로필은 DB 접속 정보를 환경 변수로 받아야 합니다. 이전에는 공통 설정의 개발용 기본값
 * (localhost·smartcollab/smartcollab)을 물려받아, 환경 변수를 빠뜨리면 조용히 그 값으로 접속을 시도했습니다.
 */
class ProductionSettingsTest {

    /** 설정 파일을 실제 우선순위(프로필 파일 → 공통 파일)대로 넣고, 시스템 환경 변수는 넣지 않습니다. */
    private static StandardEnvironment environment(Map<String, Object> variables, String... profiles) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new MapPropertySource("variables", variables));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (profiles.length > 0) {
            loader.load("application-prod.yml", new ClassPathResource("application-prod.yml")).forEach(sources::addLast);
        }
        loader.load("application.yml", new ClassPathResource("application.yml")).forEach(sources::addLast);
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    @DisplayName("[S-14] 운영 프로필에서 DB 환경 변수가 없으면 개발용 기본값으로 접속하지 않고 기동을 멈춘다")
    void prodRequiresDatabaseVariables() throws IOException {
        StandardEnvironment prod = environment(Map.of("DB_URL", "jdbc:mysql://db/smartcollab"), "prod");
        assertThat(prod.getProperty("spring.datasource.password")).isEmpty();

        assertThatThrownBy(() -> new ProductionSettings().postProcessEnvironment(prod, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_USERNAME")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageNotContaining("DB_URL");
    }

    @Test
    @DisplayName("[S-14] DB 환경 변수를 모두 주면 운영 프로필로 기동하고, 개발 프로필은 기본값을 그대로 쓴다")
    void passesWhenProvidedOrNotProd() throws IOException {
        StandardEnvironment prod = environment(Map.of("DB_URL", "jdbc:mysql://db/smartcollab",
                "DB_USERNAME", "app", "DB_PASSWORD", "secret"), "prod");
        assertThatCode(() -> new ProductionSettings().postProcessEnvironment(prod, null)).doesNotThrowAnyException();
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("secret");

        StandardEnvironment dev = environment(Map.of());
        assertThatCode(() -> new ProductionSettings().postProcessEnvironment(dev, null)).doesNotThrowAnyException();
        assertThat(dev.getProperty("spring.datasource.password")).isEqualTo("smartcollab");
    }

    @Test
    @DisplayName("[S-14] 확인 로직은 Spring Boot 기동 과정에 등록되어 있다")
    void registeredWithSpringBoot() {
        // 다른 등록 항목은 생성자 인자가 필요해 만들지 못할 수 있으므로, 실패는 무시하고 우리 항목만 확인합니다.
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation()
                .load(EnvironmentPostProcessor.class, null, (type, name, failure) -> { }))
                .anyMatch(ProductionSettings.class::isInstance);
    }
}
