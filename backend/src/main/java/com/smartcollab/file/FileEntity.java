package com.smartcollab.file;

import com.smartcollab.folder.Folder;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 파일 메타데이터. 실제 바이트는 {@link FileVersion#getStoredPath()} 가 가리키는 저장소 키에 있습니다.
 * 모든 파일은 버전이 1개 이상이며, {@code activeVersion} 이 현재 내용입니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "files")
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "folder_id", nullable = false)
    private Folder folder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "original_name", nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private long size;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_version_id")
    private FileVersion activeVersion;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 동시 수정 감지(낙관적 잠금). 두 사람이 같은 파일을 동시에 저장하면 한쪽은 409 로 거절됩니다. */
    @Version
    @Column(nullable = false)
    private long version;

    public FileEntity(Folder folder, User owner, String name, long size) {
        this.folder = folder;
        this.owner = owner;
        this.name = name;
        this.size = size;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void activate(FileVersion version) {
        this.activeVersion = version;
        this.size = version.getSize();
        this.updatedAt = Instant.now();
    }

    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = Instant.now();
    }

    public void moveTo(Folder target) {
        this.folder = target;
        this.updatedAt = Instant.now();
    }

    public void moveToTrash(User by) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = by;
    }

    public void restoreFromTrash() {
        this.deleted = false;
        this.deletedAt = null;
        this.deletedBy = null;
    }

    /** 개별로 휴지통에 있거나, 휴지통에 있는 폴더 안에 있으면 true [UX-06] */
    public boolean isInTrash() {
        return deleted || folder.isInTrash();
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }
}
