package com.smartcollab.chat;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chat", description = "팀 채팅 (실시간 전송은 STOMP /app/teams/{teamId}/chat)")
@RestController
@RequestMapping("/api/teams/{teamId}/messages")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "채팅 기록 (커서 페이지)", description = "before 보다 오래된 메시지를 size 개(기본 30, 최대 100)")
    @GetMapping
    public ChatDtos.Page history(@PathVariable Long teamId, @RequestParam(required = false) Long before,
                                 @RequestParam(required = false) Integer size, @CurrentUser AuthUser user) {
        return chatService.history(teamId, before, size, user.id());
    }

    @Operation(summary = "메시지 전송 (HTTP)", description = "WebSocket 을 쓸 수 없는 클라이언트용. 결과는 STOMP 구독자에게도 전달됩니다.")
    @PostMapping
    public ResponseEntity<ChatDtos.MessageResponse> post(@PathVariable Long teamId,
                                                         @RequestBody ChatDtos.SendRequest request,
                                                         @CurrentUser AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.post(teamId, request, user.id()));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@PathVariable Long teamId, @CurrentUser AuthUser user) {
        chatService.clear(teamId, user.id());
        return ResponseEntity.noContent().build();
    }
}
