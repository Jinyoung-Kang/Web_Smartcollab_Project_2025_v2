package com.smartcollab.file;

import com.smartcollab.access.Access;
import com.smartcollab.access.AccessPolicy;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.event.FileEvents;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.global.util.FileNames;
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

/**
 * 텍스트 편집·버전 기록·버전 복원. 서명은 서명 모듈(SignatureService)이 맡습니다.
 * <p>한 파일의 내용 변경(저장·복원)과 서명(서명 모듈)은 파일 행 잠금으로 차례로 처리합니다 [S-17]. 서명은 잠근 뒤 읽은 현재 버전에 붙고,
 * 뒤이은 내용 변경은 그 서명까지 무효로 합니다. 이전에는 서명이 잠그지 않아, 저장이 버전을 바꾸고 서명을 무효로 하는 사이에
 * 끼어들면 밀려난 버전에 유효한 서명이 남았습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class FileContentService {

    private final FileService fileService;
    private final FileVersionRepository versions;
    private final VersionSignatures versionSignatures;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final BlobStorage storage;
    private final BlobLifecycle blobLifecycle;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final StorageQuota quota;
    private final TransactionRunner tx;

    /**
     * 텍스트 내용. 권한·크기 확인은 짧은 읽기 트랜잭션에서 하고, 저장소 읽기는 트랜잭션 밖에서 해 그동안 DB 커넥션을
     * 붙잡지 않습니다 [P-03]. 읽는 사이에 새 버전이 저장돼도 확인한 버전의 내용(저장소 파일은 버전마다 따로)을 돌려줍니다.
     */
    public TextContent readText(Long fileId, Long userId) {
        TextTarget target = tx.readOnly(() -> {
            FileEntity file = fileService.getActive(fileId);
            Access access = accessPolicy.requireFileRead(file, userId);
            requireText(file);
            FileVersion active = file.getActiveVersion();
            if (active.getSize() > props.files().textEditMaxBytes()) {
                throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "편집기로 열 수 있는 크기를 넘었습니다. 내려받아 확인하세요.");
            }
            return new TextTarget(file.getName(), file.getFolder().getId(), file.getFolder().teamId(),
                    active.getStoredPath(), active.getId(), access.canEdit(), file.getUpdatedAt());
        });
        return new TextContent(target.name(), target.folderId(), target.teamId(), readUtf8(target.storedPath()),
                target.versionId(), target.editable(), target.updatedAt());
    }

    /**
     * 새 버전 저장 (낙관적 동시성 제어).
     * 클라이언트가 편집을 시작한 버전(baseVersionId)이 현재 활성 버전과 다르면 다른 사람이 먼저 저장한 것이므로 409 를 돌려줍니다.
     * v1 은 나중에 저장한 사람이 앞사람의 변경을 조용히 덮어썼습니다(lost update).
     * <p>업로드와 같은 3단계입니다 [P-01]. 이전에는 저장 공간 행을 잠근 채 저장소에 써서, 그동안 같은 개인·팀 저장 공간의
     * 업로드·폴더 변경이 모두 기다렸습니다.</p>
     * <ol>
     *   <li>짧은 읽기 트랜잭션: 권한·형식·충돌·한도를 미리 확인 (저장소에 헛되이 쓰지 않도록)</li>
     *   <li>트랜잭션 밖: 저장소에 새 버전 파일을 씀</li>
     *   <li>짧은 쓰기 트랜잭션: 잠근 뒤 다시 확인하고 버전 저장. 실패하면 2단계에서 쓴 파일을 지움</li>
     * </ol>
     */
    public SaveResult saveText(Long fileId, String content, Long baseVersionId, Long userId) {
        if (content == null) {
            throw ApiException.badRequest("내용이 없습니다.");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > props.files().textEditMaxBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "문서가 너무 큽니다.");
        }
        tx.readOnly(() -> {
            FileEntity file = requireSavable(fileId, baseVersionId, userId);
            quota.checkRoom(StorageQuota.Scope.of(file.getFolder()), bytes.length);
        });

        String key = "versions/" + UUID.randomUUID();
        StoredBlob blob = storage.put(key, new ByteArrayInputStream(bytes), bytes.length);

        try {
            return tx.write(() -> {
                FileEntity file = requireSavable(fileId, baseVersionId, userId);
                User editor = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
                // 새 버전은 저장 공간을 더 차지하므로 한도를 확인합니다 (반복 저장으로 버전을 한없이 쌓지 못하게) [SEC-05]
                quota.lockAndCheckRoom(StorageQuota.Scope.of(file.getFolder()), blob.size());
                // 서명·복원과 차례로 처리되도록 새 버전 행을 넣기 전에 파일 행을 잠급니다 [S-17]. 새 버전 INSERT 의 외래 키 확인이
                // 파일 행에 공유 잠금을 걸고 뒤이은 UPDATE 가 배타 잠금으로 올리는 사이에, 서명이 기다리며 교착되지 않게 하려는 것입니다.
                // (이미 읽은 엔티티는 갱신되지 않지만, 그 사이 다른 변경이 있었다면 @Version 확인이 409 로 막습니다.)
                fileService.lockActive(fileId);
                FileVersion version = versions.save(new FileVersion(file, key, editor, blob.size(), blob.sha256()));
                file.activate(version);
                events.publishEvent(new FileEvents.CurrentVersionChanged(fileId));   // 기존 서명 무효 (서명 모듈)
                publishChanged(file);
                return new SaveResult(version.getId(), file.getUpdatedAt());
            });
        } catch (RuntimeException e) {
            blobLifecycle.discard(List.of(key));
            throw e;
        }
    }

    /** 저장할 수 있는지(권한·형식·편집 시작 버전) 확인합니다. 저장소에 쓰기 전과 쓴 뒤에 한 번씩 봅니다. */
    private FileEntity requireSavable(Long fileId, Long baseVersionId, Long userId) {
        FileEntity file = fileService.getActive(fileId);
        accessPolicy.requireFileEdit(file, userId);
        requireText(file);
        if (baseVersionId == null || !baseVersionId.equals(file.getActiveVersion().getId())) {
            throw new ApiException(ErrorCode.EDIT_CONFLICT);
        }
        return file;
    }

    /** 버전 기록 + 버전별 서명. 쿼리 2회 (v1 은 버전마다 서명 목록을 다시 조회). */
    @Transactional(readOnly = true)
    public List<VersionResponse> history(Long fileId, Long userId) {
        FileEntity file = fileService.getActive(fileId);
        accessPolicy.requireFileRead(file, userId);
        Long activeId = file.getActiveVersion().getId();
        Map<Long, List<SignatureResponse>> signaturesByVersion = versionSignatures.byVersion(fileId);
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
        events.publishEvent(new FileEvents.CurrentVersionChanged(fileId));
        publishChanged(file);
    }

    private String readUtf8(String key) {
        try (InputStream in = storage.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 요약·번역용 텍스트 */
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
            events.publishEvent(new ChangeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    public record TextContent(String name, Long folderId, Long teamId, String content, Long versionId, boolean editable,
                              Instant updatedAt) {
    }

    /** 텍스트 읽기의 트랜잭션 안 확인 결과 — 내용은 트랜잭션 밖에서 storedPath 로 읽습니다 */
    private record TextTarget(String name, Long folderId, Long teamId, String storedPath, Long versionId, boolean editable,
                              Instant updatedAt) {
    }

    public record SaveResult(Long versionId, Instant updatedAt) {
    }

    public record SignatureResponse(String signerName, Instant signedAt, boolean valid, String sha256) {
    }

    public record VersionResponse(Long versionId, Instant createdAt, String editorName, long size, String sha256,
                                  boolean active, List<SignatureResponse> signatures) {
    }
}
