package com.smartcollab.share;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * 비밀번호를 확인한 공유 링크에 대해 짧게 유효한 다운로드 허가(grant)를 서명해 발급합니다.
 * 형식: {@code 만료시각(epoch초).HMAC-SHA256(token|만료시각)}.
 * <p>v1 은 비밀번호를 URL 쿼리(?password=)로 보내 서버·프록시 로그와 브라우저 기록에 평문으로 남았습니다.</p>
 */
@Component
public class DownloadGrantSigner {

    static final Duration TTL = Duration.ofMinutes(5);

    private final byte[] key = new byte[32];
    private final Clock clock;

    public DownloadGrantSigner() {
        this(Clock.systemUTC());
    }

    DownloadGrantSigner(Clock clock) {
        new SecureRandom().nextBytes(key);
        this.clock = clock;
    }

    public String issue(String token) {
        long expires = clock.instant().plus(TTL).getEpochSecond();
        return expires + "." + sign(token, expires);
    }

    public boolean verify(String token, String grant) {
        if (grant == null) return false;
        int dot = grant.indexOf('.');
        if (dot <= 0) return false;
        long expires;
        try {
            expires = Long.parseLong(grant.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expires) return false;
        byte[] expected = sign(token, expires).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = grant.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);   // 상수 시간 비교 (타이밍 공격 방지)
    }

    private String sign(String token, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((token + "|" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
