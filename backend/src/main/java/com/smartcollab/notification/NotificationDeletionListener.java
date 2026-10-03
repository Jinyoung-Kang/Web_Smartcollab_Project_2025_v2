package com.smartcollab.notification;

import com.smartcollab.event.DeletionEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 계정 삭제 때 그 사용자의 알림을 지웁니다 [A-02]. 지우는 쪽의 트랜잭션 안에서 동기로 실행됩니다. */
@Component
@RequiredArgsConstructor
class NotificationDeletionListener {

    private final NotificationRepository notifications;

    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_NOTIFICATIONS)
    void deleteNotifications(DeletionEvents.AccountDeleting event) {
        notifications.deleteByUser(event.userId());
    }
}
