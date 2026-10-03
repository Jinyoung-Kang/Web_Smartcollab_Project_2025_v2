package com.smartcollab.global.web;

import com.smartcollab.global.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * 스프링 시큐리티(order -100)보다 먼저 실행되는 요청 필터들.
 */
@Configuration
public class WebFilterConfig {

    /** 가장 먼저: 이후 모든 로그와 오류 응답(413 포함)에 추적 ID 가 붙도록 합니다. */
    static final int TRACE_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    /** 보안 필터보다 먼저: 인증·CSRF 처리 전에 너무 큰 본문을 거절합니다. */
    static final int BODY_LIMIT_ORDER = -150;

    @Bean
    FilterRegistrationBean<RequestTraceFilter> requestTraceFilter() {
        FilterRegistrationBean<RequestTraceFilter> registration = new FilterRegistrationBean<>(new RequestTraceFilter());
        registration.setOrder(TRACE_ORDER);
        return registration;
    }

    /** 요청을 보낸 탭의 ID 를 실시간 이벤트에 싣기 위해 [IMP-03] */
    @Bean
    FilterRegistrationBean<RequestOriginFilter> requestOriginFilter() {
        FilterRegistrationBean<RequestOriginFilter> registration = new FilterRegistrationBean<>(new RequestOriginFilter());
        registration.setOrder(TRACE_ORDER + 1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(AppProperties props, ObjectMapper mapper) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(props.http().maxBodySize().toBytes(), mapper));
        registration.setOrder(BODY_LIMIT_ORDER);
        return registration;
    }
}
