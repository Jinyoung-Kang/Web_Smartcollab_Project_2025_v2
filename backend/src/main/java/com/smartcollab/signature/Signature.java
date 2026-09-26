package com.smartcollab.signature;

import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileVersion;
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
 * 특정 버전에 대한 승인 서명. 서명 당시 버전의 SHA-256 을 함께 남기며,
 * 파일 내용이 바뀌면(새 버전 저장·복원) 기존 서명은 무효가 됩니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "signatures")
public class Signature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signature_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private FileVersion fileVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signer_id", nullable = false)
    private User signer;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "is_valid", nullable = false)
    private boolean valid;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private Instant signedAt;

    public Signature(FileEntity file, FileVersion version, User signer) {
        this.file = file;
        this.fileVersion = version;
        this.signer = signer;
        this.sha256 = version.getSha256();
        this.valid = true;
        this.signedAt = Instant.now();
    }
}
