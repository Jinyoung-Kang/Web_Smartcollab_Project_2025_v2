package com.smartcollab.global.web;

import com.smartcollab.global.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 스프링 시큐리티(order -100)보다 먼저 실행되는 요청 필터들.
 */
@Configuration
public class WebFilterConfig {

    /** 보안 필터보다 먼저: 인증·CSRF 처리 전에 너무 큰 본문을 거절합니다. */
    static final int BODY_LIMIT_ORDER = -150;

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(AppProperties props, ObjectMapper mapper) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(props.http().maxBodySize().toBytes(), mapper));
        registration.setOrder(BODY_LIMIT_ORDER);
        return registration;
    }
}
