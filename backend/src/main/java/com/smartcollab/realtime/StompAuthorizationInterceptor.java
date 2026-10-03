package com.smartcollab.realtime;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 프레임 단위 인가.
 * <ul>
 *   <li>CONNECT: 인증된 핸드셰이크만 허용</li>
 *   <li>SUBSCRIBE: 팀 토픽은 그 팀 멤버만, 개인 큐는 본인만 (그 외 목적지는 거부)</li>
 *   <li>SEND: /app/** 만 허용 (보낸 사람은 페이로드가 아니라 Principal 로 결정)</li>
 *   <li>로그인(JWT)이 만료된 세션의 CONNECT·SUBSCRIBE·SEND 는 거절 — 연결은 핸드셰이크 때 한 번만 인증되기 때문 [SEC-02]</li>
 *   <li>한 연결에서 같은 목적지를 다시 구독하거나 구독 수가 상한을 넘으면, DB 를 조회하기 전에 거절 [S-11]</li>
 * </ul>
 * <p>거절하면 Spring 이 ERROR 프레임을 보내고 연결을 닫습니다(별도 오류 처리기를 두지 않음). 화면은 끊긴 뒤 다시 연결해
 * 목적지별로 한 번씩만 구독합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class StompAuthorizationInterceptor implements ChannelInterceptor {

    static final Pattern TEAM_TOPIC = Pattern.compile("^/topic/teams/(\\d+)/(chat|events|presence)$");

    /**
     * 연결 하나가 가질 수 있는 구독 수 [S-11]. 화면은 내 모든 팀의 chat·events 를 구독하므로(팀당 2개 + 알림·접속자)
     * 정상 사용보다 훨씬 크게 잡고, 연결별 메모리와 구독 처리량의 끝을 정하는 용도로만 씁니다.
     */
    static final int MAX_SUBSCRIPTIONS_PER_SESSION = 500;

    private final AccessPolicy accessPolicy;

    /** sessionId → (subscriptionId → 목적지). 중복 구독과 상한을 DB 조회 전에 판단합니다 [S-11]. */
    private final Map<String, Map<String, String>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
            AuthUser user = authUser(accessor.getUser());
            if (user == null) {
                throw new MessageDeliveryException("인증되지 않은 연결입니다.");
            }
            Instant expiresAt = JwtTokenService.expiresAt(accessor.getUser());
            if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
                throw new MessageDeliveryException("로그인이 만료되었습니다. 다시 로그인하세요.");
            }
            if (command == StompCommand.SUBSCRIBE) {
                reserve(accessor);
                try {
                    authorizeSubscribe(accessor.getDestination(), user);
                } catch (RuntimeException e) {
                    release(accessor.getSessionId(), accessor.getSubscriptionId());
                    throw e;
                }
            } else if (command == StompCommand.SEND) {
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new MessageDeliveryException("허용되지 않은 전송 목적지입니다.");
                }
            }
        } else if (command == StompCommand.UNSUBSCRIBE) {
            release(accessor.getSessionId(), accessor.getSubscriptionId());
        } else if (command == StompCommand.DISCONNECT && accessor.getSessionId() != null) {
            // 클라이언트가 보낸 DISCONNECT 와, 연결이 끊길 때 Spring 이 대신 보내는 DISCONNECT 모두 여기를 지납니다.
            subscriptions.remove(accessor.getSessionId());
        }
        return message;
    }

    private void reserve(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (destination == null || sessionId == null || accessor.getSubscriptionId() == null) {
            throw new MessageDeliveryException("구독 목적지가 없습니다.");
        }
        subscriptions.compute(sessionId, (id, subs) -> {
            Map<String, String> current = subs == null ? new HashMap<>() : subs;
            if (current.containsValue(destination)) {
                throw new MessageDeliveryException("이미 구독 중인 목적지입니다: " + destination);
            }
            if (current.size() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
                throw new MessageDeliveryException("구독은 연결 하나당 " + MAX_SUBSCRIPTIONS_PER_SESSION + "개까지입니다.");
            }
            current.put(accessor.getSubscriptionId(), destination);
            return current;
        });
    }

    private void release(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) return;
        subscriptions.computeIfPresent(sessionId, (id, subs) -> {
            subs.remove(subscriptionId);
            return subs.isEmpty() ? null : subs;
        });
    }

    /** 구독을 추적 중인 연결 수 (테스트용) */
    int trackedSessions() {
        return subscriptions.size();
    }

    private void authorizeSubscribe(String destination, AuthUser user) {
        if (destination == null) {
            throw new MessageDeliveryException("구독 목적지가 없습니다.");
        }
        if (destination.equals("/user/queue/notifications") || destination.equals("/user/queue/errors")) {
            return;
        }
        Matcher m = TEAM_TOPIC.matcher(destination);
        if (m.matches() && accessPolicy.isMember(Long.valueOf(m.group(1)), user.id())) {
            return;
        }
        throw new MessageDeliveryException("구독 권한이 없습니다: " + destination);
    }

    static AuthUser authUser(Principal principal) {
        return principal instanceof AbstractAuthenticationToken token ? JwtTokenService.toAuthUser(token) : null;
    }
}
