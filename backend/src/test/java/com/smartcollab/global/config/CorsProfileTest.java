package com.smartcollab.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 파일(application.yml + application-prod.yml)을 실제 우선순위대로 읽어 CORS 허용 출처를 확인합니다.
 */
class CorsProfileTest {

    @Test
    @DisplayName("[SEC-09] 운영 프로필은 CORS_ALLOWED_ORIGINS 를 주지 않으면 다른 출처를 허용하지 않는다")
    void prodAllowsNoCrossOriginByDefault() throws IOException {
        assertThat(allowedOrigins("application-prod.yml", "application.yml")).isEmpty();
    }

    @Test
    @DisplayName("개발(기본) 프로필은 Vite 개발 서버 출처를 허용한다")
    void devAllowsViteDevServer() throws IOException {
        assertThat(allowedOrigins("application.yml")).contains("http://localhost:5173");
    }

    /** 앞에 둔 파일이 우선합니다 (프로필 파일 → 공통 파일). 환경 변수는 넣지 않아 기본값을 확인합니다. */
    private static List<String> allowedOrigins(String... files) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        for (String file : files) {
            loader.load(file, new ClassPathResource(file)).forEach(sources::addLast);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("app.cors.allowed-origins", Bindable.listOf(String.class)).orElse(List.of());
    }
}
