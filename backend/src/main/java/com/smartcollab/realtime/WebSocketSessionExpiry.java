package com.smartcollab.realtime;

import com.smartcollab.global.security.JwtTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인(JWT)이 만료된 WebSocket 연결을 닫습니다 [SEC-02].
 * <p>WebSocket 은 핸드셰이크 때 한 번만 인증되므로, 그대로 두면 토큰이 만료된 뒤에도 연결이 살아 있는 동안
 * 채팅·이벤트를 계속 받습니다. 1분마다 만료된 세션을 닫고, 클라이언트는 재연결할 때 다시 인증을 거칩니다.</p>
 */
@Slf4j
@Component
public class WebSocketSessionExpiry {

    /** 4000~4999 는 애플리케이션 정의 종료 코드 */
    static final CloseStatus LOGIN_EXPIRED = new CloseStatus(4401, "login expired");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public WebSocketSessionExpiry() {
        this(Clock.systemUTC());
    }

    WebSocketSessionExpiry(Clock clock) {
        this.clock = clock;
    }

    /** STOMP 핸들러를 감싸 열린 세션을 기록합니다 ({@link WebSocketConfig} 에서 등록). */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    @Scheduled(fixedDelay = 60_000)
    public void closeExpired() {
        Instant now = clock.instant();
        sessions.values().forEach(session -> {
            Instant expiresAt = JwtTokenService.expiresAt(session.getPrincipal());
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                close(session);
            }
        });
    }

    int openSessions() {
        return sessions.size();
    }

    private void close(WebSocketSession session) {
        sessions.remove(session.getId());
        try {
            if (session.isOpen()) {
                session.close(LOGIN_EXPIRED);
            }
        } catch (IOException e) {
            log.debug("Failed to close expired WebSocket session {}", session.getId(), e);
        }
    }
}
