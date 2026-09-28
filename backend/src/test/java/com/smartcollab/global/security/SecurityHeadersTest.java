package com.smartcollab.global.security;

import com.smartcollab.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 보안 헤더. CSP 는 스크립트뿐 아니라 인라인 스타일도 막습니다 [SEC-12] — 스타일 주입으로 화면을 속이거나
 * CSS 선택자로 값을 빼내는 공격을 막기 위해서입니다(React 의 style 속성은 DOM API 로 적용되어 CSP 의 영향을 받지 않음).
 */
class SecurityHeadersTest extends IntegrationTest {

    @Test
    @DisplayName("[SEC-12] CSP 는 외부·인라인 스크립트와 인라인 스타일을 허용하지 않는다")
    void strictContentSecurityPolicy() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/public/config")).andReturn().getResponse();
        String csp = res.getHeader("Content-Security-Policy");

        assertThat(csp).contains("default-src 'self'", "script-src 'self'", "style-src 'self'", "object-src 'none'",
                "frame-ancestors 'self'");
        assertThat(csp).doesNotContain("'unsafe-inline'", "'unsafe-eval'");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("same-origin");
    }
}
