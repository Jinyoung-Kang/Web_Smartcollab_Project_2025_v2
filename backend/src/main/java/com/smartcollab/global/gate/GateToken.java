package com.smartcollab.global.gate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * 잠금을 푼 브라우저에 주는 서명 쿠키 값 [GATE-01] — {@code base64url("smartcollab:코드이름:만료초").HMAC-SHA256}.
 * <p>서버는 상태를 저장하지 않고 서명과 만료만 확인합니다. 코드 이름을 담아 두어, 그 코드를 목록에서 지우면 그 코드로 받은
 * 쿠키도 바로 무효가 됩니다(확인은 {@link AccessGateFilter}).</p>
 */
final class GateToken {

    static final String SERVICE = "smartcollab";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;

    GateToken(byte[] secret) {
        this.secret = secret.clone();
    }

    String sign(String codeName, long expiresAtEpochSecond) {
        String body = B64.encodeToString((SERVICE + ":" + codeName + ":" + expiresAtEpochSecond).getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(mac(body));
    }

    /** 서명이 맞고 만료되지 않았으면 코드 이름 */
    Optional<String> read(String token, long nowEpochSecond) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        try {
            if (!MessageDigest.isEqual(mac(body), B64D.decode(token.substring(dot + 1)))) {
                return Optional.empty();
            }
            String[] parts = new String(B64D.decode(body), StandardCharsets.UTF_8).split(":");
            if (parts.length != 3 || !SERVICE.equals(parts[0]) || Long.parseLong(parts[2]) <= nowEpochSecond) {
                return Optional.empty();
            }
            return Optional.of(parts[1]);
        } catch (IllegalArgumentException e) {   // base64·숫자 형식 오류
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다", e);
        }
    }
}
