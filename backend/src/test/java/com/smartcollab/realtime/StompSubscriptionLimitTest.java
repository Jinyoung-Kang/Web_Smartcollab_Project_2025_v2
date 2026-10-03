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

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S-11] 연결 하나의 구독 수. 이전에는 같은 팀 토픽을 구독 ID 만 바꿔 끝없이 구독할 수 있었고, 구독마다 DB 조회와
 * (presence 면) 모든 구독을 훑는 접속자 방송이 일어나 로그인한 사용자 한 명이 DB 커넥션과 방송을 폭주시킬 수 있었습니다.
 */
class StompSubscriptionLimitTest {

    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(accessPolicy);
    private final MessageChannel channel = mock(MessageChannel.class);

    private static final JwtAuthenticationToken USER = new JwtAuthenticationToken(Jwt.withTokenValue("t")
            .header("alg", "HS256").subject("1").claim("username", "u")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofHours(1))).build());

    private static Message<byte[]> frame(StompCommand command, String session, String subscription, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(session);
        accessor.setSubscriptionId(subscription);
        accessor.setDestination(destination);
        accessor.setUser(USER);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void subscribe(String session, String subscription, String destination) {
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, session, subscription, destination), channel);
    }

    @Test
    @DisplayName("[S-11] 한 연결에서 같은 목적지를 다시 구독하면 DB 를 조회하기 전에 거절한다")
    void duplicateDestinationIsRejectedBeforeDbLookup() {
        when(accessPolicy.isMember(any(), any())).thenReturn(true);
        subscribe("s1", "sub-0", "/topic/teams/7/chat");

        assertThatThrownBy(() -> subscribe("s1", "sub-1", "/topic/teams/7/chat"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("이미 구독 중");
        verify(accessPolicy, times(1)).isMember(7L, 1L);

        subscribe("s2", "sub-0", "/topic/teams/7/chat");   // 다른 연결(다른 탭)은 따로 셉니다
    }

    @Test
    @DisplayName("[S-11] 구독을 해제하거나 연결이 끊기면 같은 목적지를 다시 구독할 수 있다")
    void unsubscribeAndDisconnectReleaseSlots() {
        when(accessPolicy.isMember(any(), any())).thenReturn(true);
        subscribe("s1", "sub-0", "/topic/teams/7/chat");
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "s1", "sub-0", null), channel);
        subscribe("s1", "sub-1", "/topic/teams/7/chat");

        interceptor.preSend(frame(StompCommand.DISCONNECT, "s1", null, null), channel);
        assertThat(interceptor.trackedSessions()).isZero();
        subscribe("s1", "sub-2", "/topic/teams/7/chat");
    }

    @Test
    @DisplayName("[S-11] 연결 하나의 구독은 상한까지만 받는다")
    void subscriptionsPerSessionAreCapped() {
        when(accessPolicy.isMember(any(), any())).thenReturn(true);
        int max = StompAuthorizationInterceptor.MAX_SUBSCRIPTIONS_PER_SESSION;
        for (int i = 0; i < max; i++) {
            subscribe("s1", "sub-" + i, "/topic/teams/" + i + "/chat");
        }
        assertThatThrownBy(() -> subscribe("s1", "sub-x", "/topic/teams/" + max + "/chat"))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("구독은 연결 하나당 " + max + "개까지");
    }

    @Test
    @DisplayName("[S-11] 권한이 없어 거절된 구독은 자리를 차지하지 않는다")
    void rejectedSubscriptionDoesNotTakeSlot() {
        when(accessPolicy.isMember(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> subscribe("s1", "sub-0", "/topic/teams/7/chat")).isInstanceOf(MessageDeliveryException.class);
        when(accessPolicy.isMember(any(), any())).thenReturn(true);
        subscribe("s1", "sub-1", "/topic/teams/7/chat");
    }
}
