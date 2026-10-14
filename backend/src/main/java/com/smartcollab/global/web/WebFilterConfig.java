package com.smartcollab.global.web;

import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.gate.AccessGateFilter;
import com.smartcollab.global.gate.GateNotifier;
import com.smartcollab.global.gate.GateProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

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

    /** Tomcat 이 라우팅 전에 거절한 요청도 problem+json 으로 [QA-08] */
    @Bean
    ProblemErrorReportValve.Installer problemErrorReportValve(ObjectMapper mapper) {
        return new ProblemErrorReportValve.Installer(mapper);
    }

    /** 요청을 보낸 탭의 ID 를 실시간 이벤트에 싣기 위해 [IMP-03] */
    @Bean
    FilterRegistrationBean<RequestOriginFilter> requestOriginFilter() {
        FilterRegistrationBean<RequestOriginFilter> registration = new FilterRegistrationBean<>(new RequestOriginFilter());
        registration.setOrder(TRACE_ORDER + 1);
        return registration;
    }

    /** 접근 코드 잠금 — 추적 ID 를 정한 직후, 본문 제한·보안 필터보다 먼저. GATE_CODES 가 없으면 아무것도 하지 않습니다 [GATE-01] */
    @Bean
    FilterRegistrationBean<AccessGateFilter> accessGateFilter(GateProperties gate, AppProperties props, ObjectMapper mapper,
                                                              GateNotifier gateNotifier) {
        FilterRegistrationBean<AccessGateFilter> registration = new FilterRegistrationBean<>(
                new AccessGateFilter(gate, props.cookie().secure(), props.demo().enabled(), mapper, Clock.systemUTC(), gateNotifier));
        registration.setOrder(TRACE_ORDER + 2);
        return registration;
    }

    /** 잠금 해제·차단 알림(텔레그램) — 빈으로 두어 앱이 멈출 때 보내는 스레드를 닫습니다. 설정이 없으면 아무것도 하지 않습니다 [GATE-02] */
    @Bean(destroyMethod = "close")
    GateNotifier gateNotifier(GateProperties gate, ObjectMapper mapper) {
        return GateNotifier.create(gate, mapper, Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(AppProperties props, ObjectMapper mapper) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(props.http().maxBodySize().toBytes(), mapper));
        registration.setOrder(BODY_LIMIT_ORDER);
        return registration;
    }
}
