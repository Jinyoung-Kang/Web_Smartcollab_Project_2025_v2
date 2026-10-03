package com.smartcollab.file;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.util.FileNames;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.signature.Signature;
import com.smartcollab.signature.SignatureRepository;
import com.smartcollab.storage.BlobLifecycle;
import com.smartcollab.storage.BlobStorage;
import com.smartcollab.storage.StoredBlob;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 텍스트 편집·버전 기록·버전 복원·서명.
 * <p>한 파일의 내용 변경(저장·복원)과 서명은 파일 행 잠금으로 차례로 처리합니다 [S-17]. 서명은 잠근 뒤 읽은 현재 버전에 붙고,
 * 뒤이은 내용 변경은 그 서명까지 무효로 합니다. 이전에는 서명이 잠그지 않아, 저장이 버전을 바꾸고 서명을 무효로 하는 사이에
 * 끼어들면 밀려난 버전에 유효한 서명이 남았습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class FileContentService {

    private final FileService fileService;
    private final FileVersionRepository versions;
    private final SignatureRepository signatures;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final BlobStorage storage;
    private final BlobLifecycle blobLifecycle;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final StorageQuota quota;

    @Transactional(readOnly = true)
    public TextContent readText(Long fileId, Long userId) {
        FileEntity file = fileService.getActive(fileId);
        Access access = accessPolicy.requireFileRead(file, userId);
        requireText(file);
        FileVersion active = file.getActiveVersion();
        if (active.getSize() > props.files().textEditMaxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "편집기로 열 수 있는 크기를 넘었습니다. 내려받아 확인하세요.");
        }
        return new TextContent(file.getName(), file.getFolder().getId(), file.getFolder().teamId(),
                readUtf8(active.getStoredPath()), active.getId(), access.canEdit(), file.getUpdatedAt());
    }

    /**
     * 새 버전 저장 (낙관적 동시성 제어).
     * 클라이언트가 편집을 시작한 버전(baseVersionId)이 현재 활성 버전과 다르면 다른 사람이 먼저 저장한 것이므로 409 를 돌려줍니다.
     * v1 은 나중에 저장한 사람이 앞사람의 변경을 조용히 덮어썼습니다(lost update).
     */
    @Transactional
    public SaveResult saveText(Long fileId, String content, Long baseVersionId, Long userId) {
        FileEntity file = fileService.getActive(fileId);
        accessPolicy.requireFileEdit(file, userId);
        requireText(file);
        if (content == null) {
            throw ApiException.badRequest("내용이 없습니다.");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > props.files().textEditMaxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "문서가 너무 큽니다.");
        }
        if (baseVersionId == null || !baseVersionId.equals(file.getActiveVersion().getId())) {
            throw new ApiException(ErrorCode.EDIT_CONFLICT);
        }
        User editor = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        // 새 버전은 저장 공간을 더 차지하므로 한도를 확인합니다 (반복 저장으로 버전을 한없이 쌓지 못하게) [SEC-05]
        quota.lockAndCheckRoom(StorageQuota.Scope.of(file.getFolder()), bytes.length);
        // 서명·복원과 차례로 처리되도록 새 버전 행을 넣기 전에 파일 행을 잠급니다 [S-17]. 새 버전 INSERT 의 외래 키 확인이
        // 파일 행에 공유 잠금을 걸고 뒤이은 UPDATE 가 배타 잠금으로 올리는 사이에, 서명이 기다리며 교착되지 않게 하려는 것입니다.
        // (이미 읽은 엔티티는 갱신되지 않지만, 그 사이 다른 변경이 있었다면 @Version 확인이 409 로 막습니다.)
        fileService.lockActive(fileId);

        String key = "versions/" + UUID.randomUUID();
        StoredBlob blob = blobLifecycle.putWithRollbackCleanup(key, new ByteArrayInputStream(bytes), bytes.length);
        FileVersion version = versions.save(new FileVersion(file, key, editor, blob.size(), blob.sha256()));
        file.activate(version);
        signatures.invalidateAll(fileId);   // 내용이 바뀌었으므로 기존 서명은 무효
        publishChanged(file);
        return new SaveResult(version.getId(), file.getUpdatedAt());
    }

    /** 버전 기록 + 버전별 서명. 쿼리 2회 (v1 은 버전마다 서명 목록을 다시 조회). */
    @Transactional(readOnly = true)
    public List<VersionResponse> history(Long fileId, Long userId) {
        FileEntity file = fileService.getActive(fileId);
        accessPolicy.requireFileRead(file, userId);
        Long activeId = file.getActiveVersion().getId();
        Map<Long, List<SignatureResponse>> signaturesByVersion = signatures.findByFile(fileId).stream()
                .collect(Collectors.groupingBy(s -> s.getFileVersion().getId(),
                        Collectors.mapping(SignatureResponse::of, Collectors.toList())));
        return versions.findHistory(fileId).stream()
                .map(v -> new VersionResponse(v.getId(), v.getCreatedAt(), v.getEditor().getName(), v.getSize(),
                        v.getSha256(), v.getId().equals(activeId),
                        signaturesByVersion.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restore(Long fileId, Long versionId, Long userId) {
        FileEntity file = fileService.lockActive(fileId);   // [S-17]
        accessPolicy.requireFileEdit(file, userId);
        FileVersion version = versions.findById(versionId)
                .filter(v -> v.getFile().getId().equals(fileId))   // v1 은 다른 파일의 버전 ID 로도 복원이 가능했음
                .orElseThrow(() -> ApiException.notFound("버전"));
        if (version.getId().equals(file.getActiveVersion().getId())) {
            return;
        }
        file.activate(version);
        signatures.invalidateAll(fileId);
        publishChanged(file);
    }

    /**
     * 현재 버전에 서명합니다. 개인 파일은 소유자, 팀 파일은 팀장만 서명할 수 있습니다.
     * v1 은 "가장 최근에 만들어진 버전"에 서명해, 옛 버전을 복원한 뒤 서명하면 엉뚱한 버전에 서명됐습니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void sign(Long fileId, Long userId) {
        FileEntity file = fileService.lockActive(fileId);   // 저장·복원이 끝난 뒤의 현재 버전에 서명 [S-17]
        Access access = accessPolicy.requireFileRead(file, userId);
        boolean allowed = access.isTeam() ? access.leader() : file.isOwnedBy(userId);
        if (!allowed) {
            throw ApiException.forbidden(access.isTeam() ? "팀 파일은 팀장만 서명할 수 있습니다." : "파일 소유자만 서명할 수 있습니다.");
        }
        FileVersion active = file.getActiveVersion();
        if (signatures.existsByFileVersionIdAndSignerId(active.getId(), userId)) {
            throw ApiException.conflict("이미 이 버전에 서명했습니다.");
        }
        signatures.save(new Signature(file, active, users.getReferenceById(userId)));
        publishChanged(file);
    }

    private String readUtf8(String key) {
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 요약·번역용 텍스트. readText 를 내부 호출하므로 여기에도 트랜잭션이 필요합니다(프록시 우회 방지). */
    @Transactional(readOnly = true)
    public String readUtf8Content(Long fileId, Long userId, long maxBytes) {
        TextContent text = readText(fileId, userId);
        if (text.content().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "처리할 수 있는 문서 크기를 넘었습니다.");
        }
        return text.content();
    }

    private static void requireText(FileEntity file) {
        if (!FileNames.isTextEditable(file.getName())) {
            throw ApiException.badRequest("텍스트 문서(txt, md, log, csv, json)만 지원합니다.");
        }
    }

    private void publishChanged(FileEntity file) {
        Long teamId = file.getFolder().teamId();
        if (teamId != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    public record TextContent(String name, Long folderId, Long teamId, String content, Long versionId, boolean editable,
                              Instant updatedAt) {
    }

    public record SaveResult(Long versionId, Instant updatedAt) {
    }

    public record SignatureResponse(String signerName, Instant signedAt, boolean valid, String sha256) {
        static SignatureResponse of(Signature s) {
            return new SignatureResponse(s.getSigner().getName(), s.getSignedAt(), s.isValid(), s.getSha256());
        }
    }

    public record VersionResponse(Long versionId, Instant createdAt, String editorName, long size, String sha256,
                                  boolean active, List<SignatureResponse> signatures) {
    }
}
