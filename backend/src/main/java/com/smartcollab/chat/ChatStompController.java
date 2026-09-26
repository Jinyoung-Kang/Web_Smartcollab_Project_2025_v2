package com.smartcollab.chat;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP 로 들어온 채팅 전송. 저장 후 커밋되면 {@code /topic/teams/{teamId}/chat} 으로 방송됩니다.
 */
@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;

    @MessageMapping("/teams/{teamId}/chat")
    public void send(@DestinationVariable Long teamId, @Payload ChatDtos.SendRequest request, Principal principal) {
        AuthUser user = principal instanceof AbstractAuthenticationToken token ? JwtTokenService.toAuthUser(token) : null;
        if (user == null) {
            throw new IllegalStateException("인증되지 않은 사용자");
        }
        chatService.post(teamId, request, user.id());
    }

    /** 처리 중 오류는 보낸 사람에게만 /user/queue/errors 로 알려 줍니다. */
    @MessageExceptionHandler(ApiException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatDtos.ErrorResponse handle(ApiException e) {
        return new ChatDtos.ErrorResponse(e.getMessage());
    }
}
