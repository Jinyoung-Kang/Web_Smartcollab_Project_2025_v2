package com.smartcollab.realtime;

import com.smartcollab.event.ChangeEvents;
import com.smartcollab.global.security.JwtTokenService;
import com.smartcollab.global.web.RequestOrigin;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 도메인 이벤트를 STOMP 목적지로 전달합니다 (커밋 이후).
 * <ul>
 *   <li>/topic/teams/{teamId}/chat — 채팅 메시지</li>
 *   <li>/topic/teams/{teamId}/events — 폴더 변경·멤버 변경 등 화면 갱신 신호</li>
 *   <li>/user/queue/notifications — 개인 알림 (v1 의 10초 폴링을 대체)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RealtimePublisher {

    private final SimpMessagingTemplate messaging;

    public static String chatTopic(Long teamId) {
        return "/topic/teams/" + teamId + "/chat";
    }

    public static String eventsTopic(Long teamId) {
        return "/topic/teams/" + teamId + "/events";
    }

    public static String presenceTopic(Long teamId) {
        return "/topic/teams/" + teamId + "/presence";
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.FolderChanged event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "FOLDER_CHANGED");
        payload.put("folderId", event.folderId());
        // 이 변경을 일으킨 탭의 ID — 그 탭은 자기 변경으로 목록을 다시 불러오지 않습니다 [IMP-03].
        // 탭 ID 는 요청자가 정하는 값이라 요청한 사용자 ID 와 함께 싣고, 화면은 둘 다 자기 것일 때만 건너뜁니다 —
        // 탭 ID 만 실으면 다른 팀원이 이벤트로 본 남의 탭 ID 를 보내 그 탭의 목록 갱신을 막을 수 있었습니다 [L-3]
        Long requester = JwtTokenService.userIdOf(SecurityContextHolder.getContext().getAuthentication());
        RequestOrigin.current().filter(origin -> requester != null).ifPresent(origin -> {
            payload.put("origin", origin);
            payload.put("originUserId", requester);
        });
        messaging.convertAndSend(eventsTopic(event.teamId()), (Object) payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.TeamChanged event) {
        messaging.convertAndSend(eventsTopic(event.teamId()),
                (Object) Map.of("type", event.change().name(), "teamId", event.teamId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.UserNotified event) {
        messaging.convertAndSendToUser(String.valueOf(event.userId()), "/queue/notifications", event.payload());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.ChatPosted event) {
        messaging.convertAndSend(chatTopic(event.teamId()), event.payload());
    }
}
