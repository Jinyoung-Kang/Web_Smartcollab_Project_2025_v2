package com.smartcollab.global.gate;

import com.smartcollab.global.config.AppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 접근 코드 잠금 설정 (app.gate.*) [GATE-01]. {@code codes} 가 비어 있으면 잠금을 쓰지 않습니다(개발·시험 기본값).
 *
 * @param codes     {@code 이름:scrypt해시} 목록 (쉼표 구분)
 * @param secret    쿠키 서명 키 — 잠금을 쓰면 32바이트 이상 필수
 * @param hours     잠금화면에 적을 운영 시간 (비우면 줄을 감춤)
 * @param cookieTtl 잠금을 푼 뒤 다시 묻기까지의 시간
 */
@ConfigurationProperties(prefix = "app.gate")
public record GateProperties(String codes, String secret, String hours, Duration cookieTtl) {

    public GateProperties {
        codes = codes == null ? "" : codes;
        secret = secret == null ? "" : secret;
        hours = hours == null ? "" : hours.strip();
        cookieTtl = cookieTtl == null ? Duration.ofHours(12) : cookieTtl;
    }

    public boolean enabled() {
        return StringUtils.hasText(codes);
    }

    /** 코드 해시·서명 키를 가립니다 [L-4] */
    @Override
    public String toString() {
        return "GateProperties[codes=" + AppProperties.redact(codes) + ", secret=" + AppProperties.redact(secret)
                + ", hours=" + hours + ", cookieTtl=" + cookieTtl + "]";
    }
}
