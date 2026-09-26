package com.smartcollab.share;

import com.smartcollab.file.FileEntity;
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

/**
 * 로그인 없이 파일을 내려받을 수 있는 공유 링크. 비밀번호(BCrypt)·만료 시각·다운로드 횟수 제한을 걸 수 있습니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "share_links")
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "link_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "download_limit")
    private Integer downloadLimit;

    @Column(name = "download_count", nullable = false)
    private int downloadCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ShareLink(FileEntity file, User owner, String token, String passwordHash, Instant expiresAt,
                     Integer downloadLimit) {
        this.file = file;
        this.owner = owner;
        this.token = token;
        this.passwordHash = passwordHash;
        this.expiresAt = expiresAt;
        this.downloadLimit = downloadLimit;
        this.createdAt = Instant.now();
    }

    public boolean requiresPassword() {
        return passwordHash != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isExhausted() {
        return downloadLimit != null && downloadCount >= downloadLimit;
    }
}
