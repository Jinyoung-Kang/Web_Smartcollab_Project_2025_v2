package com.smartcollab.realtime;

import com.smartcollab.access.AccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [SEC-02] WebSocket 은 핸드셰이크 때 한 번만 인증되므로, 로그인이 만료된 세션을 따로 정리해야 합니다.
 */
class WebSocketSessionExpiryTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    private static JwtAuthenticationToken tokenExpiringAt(Instant expiresAt) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("1").claim("username", "u")
                .issuedAt(expiresAt.minus(Duration.ofHours(8))).expiresAt(expiresAt).build();
        return new JwtAuthenticationToken(jwt);
    }

    private static WebSocketSession openSession(String id, Instant expiresAt) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getPrincipal()).thenReturn(tokenExpiringAt(expiresAt));
        return session;
    }

    @Test
    @DisplayName("로그인이 만료된 세션만 닫는다")
    void closesOnlyExpiredSessions() throws Exception {
        WebSocketSessionExpiry expiry = new WebSocketSessionExpiry(Clock.fixed(NOW, ZoneOffset.UTC));
        WebSocketHandler handler = expiry.decorate(mock(WebSocketHandler.class));
        WebSocketSession expired = openSession("expired", NOW.minusSeconds(1));
        WebSocketSession valid = openSession("valid", NOW.plus(Duration.ofHours(1)));
        handler.afterConnectionEstablished(expired);
        handler.afterConnectionEstablished(valid);

        expiry.closeExpired();

        verify(expired).close(WebSocketSessionExpiry.LOGIN_EXPIRED);
        verify(valid, never()).close(any());
        assertThat(expiry.openSessions()).isEqualTo(1);
    }

    @Test
    @DisplayName("로그인이 만료된 세션의 SUBSCRIBE·SEND 는 거절하고, 유효한 세션은 통과시킨다")
    void interceptorRejectsExpiredLogin() {
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(mock(AccessPolicy.class));
        MessageChannel channel = mock(MessageChannel.class);

        assertThatThrownBy(() -> interceptor.preSend(send(tokenExpiringAt(Instant.now().minusSeconds(1))), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("만료");
        Message<byte[]> ok = send(tokenExpiringAt(Instant.now().plus(Duration.ofHours(1))));
        assertThat(interceptor.preSend(ok, channel)).isSameAs(ok);
    }

    private static Message<byte[]> send(JwtAuthenticationToken user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/teams/1/chat");
        accessor.setUser(user);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
