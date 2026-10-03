package com.smartcollab.global.security;

import com.smartcollab.event.DeletionEvents;
import com.smartcollab.global.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 탈퇴한 계정의 토큰 폐기 목록 [QA-07]. 탈퇴가 커밋되면 그 사용자 ID 를 토큰 유효 시간 동안 기억하고, JWT 검증({@link #validator()})과
 * WebSocket 세션 정리에서 거절합니다.
 * <p>이전에는 JWT 가 무상태라 탈퇴 뒤에도 만료(최대 8시간)까지 /api/auth/me 를 뺀 API 가 200·204 로 통해, 다른 기기에 열려 있던
 * 화면이 로그인 화면으로 돌아가지 않았습니다. 요청마다 DB 에서 사용자를 확인하지 않고 메모리에 두는 것은 요청 제한·STOMP 브로커처럼
 * 단일 인스턴스를 전제로 하기 때문입니다. 서버를 다시 시작하면 목록이 비어, 그 전에 탈퇴한 계정의 토큰은 남은 만료 시간까지 다시
 * 통합니다(다른 사용자의 데이터에는 닿지 않음 — 사용자 ID 는 재사용되지 않음, SECURITY.md "알려진 한계").</p>
 */
@Component
public class RevokedUsers {

    private final Map<Long, Instant> revokedUntil = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public RevokedUsers(AppProperties props) {
        this(props.jwt().ttl() == null ? Duration.ofHours(8) : props.jwt().ttl(), Clock.systemUTC());
    }

    public RevokedUsers(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 지금 발급돼 있을 수 있는 모든 토큰이 만료될 때까지 이 사용자의 토큰을 거절합니다. */
    public void revoke(Long userId) {
        Instant now = clock.instant();
        revokedUntil.values().removeIf(until -> !until.isAfter(now));
        revokedUntil.put(userId, now.plus(ttl));
    }

    public boolean isRevoked(Long userId) {
        Instant until = userId == null ? null : revokedUntil.get(userId);
        return until != null && until.isAfter(clock.instant());
    }

    /** 탈퇴(계정 삭제)가 커밋된 뒤에만 폐기합니다. 탈퇴가 롤백되면(비밀번호 오류·팀장인 팀 남음) 토큰은 그대로 씁니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAccountDeleted(DeletionEvents.AccountDeleting event) {
        revoke(event.userId());
    }

    /** JWT 검증 단계에서 폐기된 사용자의 토큰을 거절합니다(쿠키·Bearer·WebSocket 핸드셰이크 공통). */
    OAuth2TokenValidator<Jwt> validator() {
        return jwt -> isRevoked(subjectOf(jwt))
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "탈퇴한 계정의 토큰입니다.", null))
                : OAuth2TokenValidatorResult.success();
    }

    static Long subjectOf(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
