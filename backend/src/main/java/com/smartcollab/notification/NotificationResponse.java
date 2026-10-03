package com.smartcollab.notification;

import com.smartcollab.event.NoticeEvents;
import java.time.Instant;

public record NotificationResponse(
        Long id,
        NoticeEvents.Type type,
        String content,
        boolean read,
        Long invitationId,
        String invitationStatus,
        Long teamId,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getContent(),
                n.isRead(),
                n.getInvitation() == null ? null : n.getInvitation().getId(),
                n.getInvitation() == null ? null : n.getInvitation().getStatus().name(),
                n.getTeam() == null ? null : n.getTeam().getId(),
                n.getCreatedAt());
    }
}
