package com.smartcollab.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI smartCollabOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartCollab API")
                .version("2.0.0")
                .description("팀 파일 협업 플랫폼 SmartCollab 의 REST API. 인증은 로그인 시 발급되는 HttpOnly 쿠키(SC_AUTH)를 사용하며, "
                        + "변경 요청에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 함께 보내야 합니다."));
    }
}
