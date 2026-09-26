package com.smartcollab.chat;

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
@Table(name = "chat_messages")
public class ChatMessage {

    public enum Type {CHAT, FILE_SHARE}

    public static final int MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = MAX_LENGTH)
    private String content;

    /** 파일 공유 메시지일 때의 파일 정보. 파일이 나중에 삭제될 수 있어 FK 를 두지 않고 스냅샷으로 보관합니다. */
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private ChatMessage(Team team, User sender, Type type, String content, Long fileId, String fileName, Long fileSize) {
        this.team = team;
        this.sender = sender;
        this.type = type;
        this.content = content;
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.createdAt = Instant.now();
    }

    public static ChatMessage text(Team team, User sender, String content) {
        return new ChatMessage(team, sender, Type.CHAT, content, null, null, null);
    }

    public static ChatMessage fileShare(Team team, User sender, Long fileId, String fileName, long fileSize) {
        return new ChatMessage(team, sender, Type.FILE_SHARE, fileName, fileId, fileName, fileSize);
    }
}
