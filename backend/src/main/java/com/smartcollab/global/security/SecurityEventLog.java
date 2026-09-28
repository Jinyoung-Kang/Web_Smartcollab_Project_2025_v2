package com.smartcollab.global.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 보안 이벤트 기록 [SEC-11]. 무차별 대입·계정 탈취 시도를 나중에 추적할 수 있도록 로그인 성공·실패와 요청 제한 발동을
 * 별도 로거("security")로 남깁니다. 운영에서는 이 로거만 모아 경보를 걸 수 있습니다.
 * <p>입력한 아이디·비밀번호·공유 링크 토큰은 남기지 않습니다 — 아이디 칸에 비밀번호를 잘못 입력하는 경우가 흔하고,
 * 토큰은 그 자체로 접근 권한이기 때문입니다.</p>
 */
public final class SecurityEventLog {

    private static final Logger LOG = LoggerFactory.getLogger("security");

    private SecurityEventLog() {
    }

    public static void loginSucceeded(Long userId, String clientIp) {
        LOG.info("security-event login-succeeded user={} ip={}", userId, clientIp);
    }

    public static void loginFailed(String clientIp) {
        LOG.warn("security-event login-failed ip={}", clientIp);
    }

    /** @param scope 제한 단위 (login-ip, login-account, signup-ip, share-password …) */
    public static void rateLimited(String scope, String clientIp) {
        LOG.warn("security-event rate-limited scope={} ip={}", scope, clientIp);
    }

    public static void sharePasswordFailed(Long linkId, String clientIp) {
        LOG.warn("security-event share-password-failed link={} ip={}", linkId, clientIp);
    }
}
