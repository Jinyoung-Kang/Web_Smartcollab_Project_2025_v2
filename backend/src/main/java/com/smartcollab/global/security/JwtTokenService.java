package com.smartcollab.global.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartcollab.global.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * JWT(HS256) 발급·검증. 검증은 Spring Security OAuth2 Resource Server 가 {@link #decoder()} 로 수행합니다.
 */
@Slf4j
@Component
public class JwtTokenService {

    public static final String CLAIM_USERNAME = "username";
    private static final String ISSUER = "smartcollab";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;

    public JwtTokenService(AppProperties props, Environment env) {
        SecretKey key = new SecretKeySpec(resolveSecret(props.jwt().secret(), env), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
        this.ttl = props.jwt().ttl() == null ? Duration.ofHours(8) : props.jwt().ttl();
    }

    private static byte[] resolveSecret(String secret, Environment env) {
        if (StringUtils.hasText(secret)) {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("JWT_SECRET 은 32바이트(256bit) 이상이어야 합니다.");
            }
            return bytes;
        }
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("운영(prod) 프로필에서는 JWT_SECRET 환경 변수가 필수입니다.");
        }
        log.warn("JWT_SECRET 이 없어 임시 키를 생성했습니다. 서버를 재시작하면 모든 로그인이 만료됩니다 (개발 전용).");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return random;
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public Duration ttl() {
        return ttl;
    }

    /** 인증 객체의 토큰 만료 시각. JWT 인증이 아니면 null. (WebSocket 세션은 연결 이후 토큰을 다시 검증하지 않으므로 따로 확인) */
    public static Instant expiresAt(Principal principal) {
        return principal instanceof JwtAuthenticationToken token ? token.getToken().getExpiresAt() : null;
    }

    /** 인증 객체(HTTP·STOMP 공통)에서 사용자 정보를 꺼냅니다. */
    public static AuthUser toAuthUser(AbstractAuthenticationToken authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return new AuthUser(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(CLAIM_USERNAME));
        }
        return null;
    }
}
