package com.smartcollab.notification;

import com.smartcollab.team.Invitation;
import com.smartcollab.team.Team;
import com.smartcollab.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications")
public class Notification {

    public enum Type {
        TEAM_INVITE,
        INVITE_ACCEPTED,
        INVITE_REJECTED,
        PERMISSION_CHANGED,
        REMOVED_FROM_TEAM,
        LEADERSHIP_TRANSFERRED,
        TEAM_DELETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Type type;

    @Column(nullable = false, length = 500)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id")
    private Invitation invitation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Notification(User user, Type type, String content, Invitation invitation, Team team) {
        this.user = user;
        this.type = type;
        this.content = content.length() > 500 ? content.substring(0, 500) : content;
        this.invitation = invitation;
        this.team = team;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        this.read = true;
    }
}
