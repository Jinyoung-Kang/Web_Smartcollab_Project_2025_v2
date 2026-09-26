package com.smartcollab.file;

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
 * 파일의 한 버전. 저장 시 계산한 SHA-256 으로 서명 당시 내용과 현재 내용이 같은지 검증합니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "file_versions")
public class FileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "version_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @Column(name = "stored_path", nullable = false, length = 100)
    private String storedPath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "editor_id", nullable = false)
    private User editor;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public FileVersion(FileEntity file, String storedPath, User editor, long size, String sha256) {
        this.file = file;
        this.storedPath = storedPath;
        this.editor = editor;
        this.size = size;
        this.sha256 = sha256;
        this.createdAt = Instant.now();
    }
}
