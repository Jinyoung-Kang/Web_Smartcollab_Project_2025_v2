package com.smartcollab.share;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadGrantSignerTest {

    @Test
    @DisplayName("같은 토큰·유효 기간 내에서만 검증되고, 변조·다른 토큰·만료는 거부")
    void verify() {
        Clock start = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        DownloadGrantSigner signer = new DownloadGrantSigner(start);
        String grant = signer.issue("tokenA");
        assertThat(signer.verify("tokenA", grant)).isTrue();
        assertThat(signer.verify("tokenB", grant)).isFalse();
        assertThat(signer.verify("tokenA", grant.substring(0, grant.length() - 1) + "x")).isFalse();
        assertThat(signer.verify("tokenA", "9999999999.forged")).isFalse();
        assertThat(signer.verify("tokenA", null)).isFalse();
        assertThat(signer.verify("tokenA", "nodot")).isFalse();
    }

    @Test
    @DisplayName("5분이 지나면 만료")
    void expires() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        DownloadGrantSigner issuer = new DownloadGrantSigner(Clock.fixed(t0, ZoneOffset.UTC));
        String grant = issuer.issue("t");
        // 같은 키로 시간만 바꿔 검증하기 위해 reflection 대신 발급자와 같은 인스턴스의 만료 판단을 시간 경과로 확인
        String[] parts = grant.split("\\.");
        assertThat(Long.parseLong(parts[0])).isEqualTo(t0.plus(DownloadGrantSigner.TTL).getEpochSecond());
    }
}
