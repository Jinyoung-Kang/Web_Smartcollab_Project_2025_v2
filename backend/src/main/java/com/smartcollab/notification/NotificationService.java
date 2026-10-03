package com.smartcollab.notification;

import com.smartcollab.event.ChangeEvents;
import com.smartcollab.event.NoticeEvents;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.team.Invitation;
import com.smartcollab.team.Team;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int RECENT_LIMIT = 30;

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    /**
     * 다른 모듈이 요청한 알림을 저장하고, 커밋되면 받는 사람에게 WebSocket 으로 즉시 전달합니다.
     * 요청한 쪽의 트랜잭션 안에서 동기로 실행됩니다(알림 저장이 실패하면 함께 롤백).
     */
    @EventListener
    public void onNoticeRequested(NoticeEvents.Requested request) {
        User recipient = users.getReferenceById(request.recipientUserId());
        Invitation invitation = request.invitationId() == null ? null : entityManager.getReference(Invitation.class, request.invitationId());
        Team team = request.teamId() == null ? null : entityManager.getReference(Team.class, request.teamId());
        Notification saved = notifications.save(new Notification(recipient, request.type(), request.content(), invitation, team));
        events.publishEvent(new ChangeEvents.UserNotified(recipient.getUsername(), NotificationResponse.from(saved)));
    }

    @Transactional(readOnly = true)
    public NotificationList recent(Long userId) {
        List<NotificationResponse> items = notifications.findRecent(userId, PageRequest.of(0, RECENT_LIMIT)).stream()
                .map(NotificationResponse::from)
                .toList();
        return new NotificationList(items, notifications.countByUserIdAndReadFalse(userId));
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        find(notificationId, userId).markRead();
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId);
    }

    @Transactional
    public void delete(Long notificationId, Long userId) {
        notifications.delete(find(notificationId, userId));
    }

    @Transactional
    public void deleteAll(Long userId) {
        notifications.deleteByUser(userId);
    }

    private Notification find(Long notificationId, Long userId) {
        Notification n = notifications.findById(notificationId).orElseThrow(() -> ApiException.notFound("알림"));
        if (!n.getUser().getId().equals(userId)) {
            throw ApiException.notFound("알림");
        }
        return n;
    }

    public record NotificationList(List<NotificationResponse> items, long unreadCount) {
    }
}
