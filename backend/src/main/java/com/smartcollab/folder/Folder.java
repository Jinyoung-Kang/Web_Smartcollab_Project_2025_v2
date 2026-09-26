package com.smartcollab.folder;

import com.smartcollab.team.Team;
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
import java.util.Objects;

/**
 * 계층형 폴더. 개인 스토리지(team == null) 또는 팀 스토리지(team != null) 중 한 곳(스코프)에 속합니다.
 * 스코프마다 parent == null 인 루트 폴더가 하나씩 있습니다.
 * <p>v1 의 양방향 컬렉션(subFolders/files, cascade=ALL)은 N+1 조회와 의도치 않은 연쇄 삭제의 원인이어서 제거하고
 * 필요한 조회는 리포지토리 쿼리로 명시합니다.</p>
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "folders")
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "folder_id")
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_folder_id")
    private Folder parent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Folder(String name, User owner, Team team, Folder parent) {
        this.name = name;
        this.owner = owner;
        this.team = team;
        this.parent = parent;
        this.createdAt = Instant.now();
    }

    public static Folder personalRoot(User owner) {
        return new Folder("내 드라이브", owner, null, null);
    }

    public static Folder teamRoot(Team team, User creator) {
        return new Folder(team.getName(), creator, team, null);
    }

    /** 부모와 같은 스코프에 하위 폴더를 만듭니다. */
    public static Folder childOf(Folder parent, String name, User creator) {
        return new Folder(name, creator, parent.getTeam(), parent);
    }

    public boolean isRoot() {
        return parent == null;
    }

    public Long teamId() {
        return team == null ? null : team.getId();
    }

    /** 같은 스토리지(개인 스토리지라면 같은 소유자, 팀이라면 같은 팀)에 속하는지 */
    public boolean sameScopeAs(Folder other) {
        if (team == null && other.team == null) {
            return owner.getId().equals(other.owner.getId());
        }
        return Objects.equals(teamId(), other.teamId());
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void moveUnder(Folder newParent) {
        this.parent = newParent;
    }

    public void transferOwnership(User newOwner) {
        this.owner = newOwner;
    }
}
