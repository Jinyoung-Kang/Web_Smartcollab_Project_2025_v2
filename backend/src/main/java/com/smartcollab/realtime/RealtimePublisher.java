package com.smartcollab.realtime;

import com.smartcollab.event.ChangeEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
        messaging.convertAndSend(eventsTopic(event.teamId()),
                (Object) Map.of("type", "FOLDER_CHANGED", "folderId", event.folderId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.TeamChanged event) {
        messaging.convertAndSend(eventsTopic(event.teamId()),
                (Object) Map.of("type", event.change().name(), "teamId", event.teamId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.UserNotified event) {
        messaging.convertAndSendToUser(event.username(), "/queue/notifications", event.payload());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ChangeEvents.ChatPosted event) {
        messaging.convertAndSend(chatTopic(event.teamId()), event.payload());
    }
}
