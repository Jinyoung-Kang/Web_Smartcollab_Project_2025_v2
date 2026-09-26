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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 프레임 단위 인가.
 * <ul>
 *   <li>CONNECT: 인증된 핸드셰이크만 허용</li>
 *   <li>SUBSCRIBE: 팀 토픽은 그 팀 멤버만, 개인 큐는 본인만 (그 외 목적지는 거부)</li>
 *   <li>SEND: /app/** 만 허용 (보낸 사람은 페이로드가 아니라 Principal 로 결정)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StompAuthorizationInterceptor implements ChannelInterceptor {

    static final Pattern TEAM_TOPIC = Pattern.compile("^/topic/teams/(\\d+)/(chat|events|presence)$");

    private final AccessPolicy accessPolicy;

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
            if (command == StompCommand.SUBSCRIBE) {
                authorizeSubscribe(accessor.getDestination(), user);
            } else if (command == StompCommand.SEND) {
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/")) {
                    throw new MessageDeliveryException("허용되지 않은 전송 목적지입니다.");
                }
            }
        }
        return message;
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
