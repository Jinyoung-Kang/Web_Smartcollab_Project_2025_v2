package com.smartcollab.team;

import com.smartcollab.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "teams")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 팀장. TeamMember.isTeamLeader 와 항상 함께 갱신합니다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Team(String name, User owner) {
        this.name = name;
        this.owner = owner;
        this.createdAt = Instant.now();
    }

    void changeOwner(User newOwner) {
        this.owner = newOwner;
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }
}
