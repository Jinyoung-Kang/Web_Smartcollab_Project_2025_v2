package com.smartcollab.signature;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.event.ChangeEvents;
import com.smartcollab.file.FileContentService;
import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileService;
import com.smartcollab.file.FileVersion;
import com.smartcollab.file.VersionSignatures;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 문서 서명 [A-01]. 이전에는 파일 모듈(FileContentService)에 있어 파일 ↔ 서명 모듈이 서로 의존했습니다.
 * 서명은 파일·버전을 가리키므로 서명 모듈이 파일 모듈을 쓰고, 파일 모듈은 {@link VersionSignatures} 로만 서명을 봅니다.
 */
@Service
@RequiredArgsConstructor
public class SignatureService implements VersionSignatures {

    private final SignatureRepository signatures;
    private final FileService fileService;
    private final AccessPolicy accessPolicy;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    /**
     * 현재 버전에 서명합니다. 개인 파일은 소유자, 팀 파일은 팀장만 서명할 수 있습니다.
     * v1 은 "가장 최근에 만들어진 버전"에 서명해, 옛 버전을 복원한 뒤 서명하면 엉뚱한 버전에 서명됐습니다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void sign(Long fileId, Long userId) {
        FileEntity file = fileService.lockActive(fileId);   // 저장·복원이 끝난 뒤의 현재 버전에 서명 [S-17]
        accessPolicy.requireSign(file, userId);
        FileVersion active = file.getActiveVersion();
        if (signatures.existsByFileVersionIdAndSignerId(active.getId(), userId)) {
            throw ApiException.conflict("이미 이 버전에 서명했습니다.");
        }
        signatures.save(new Signature(file, active, users.getReferenceById(userId)));
        Long teamId = file.getFolder().teamId();
        if (teamId != null) {
            events.publishEvent(new ChangeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    @Override
    public Map<Long, List<FileContentService.SignatureResponse>> byVersion(Long fileId) {
        return signatures.findByFile(fileId).stream()
                .collect(Collectors.groupingBy(s -> s.getFileVersion().getId(),
                        Collectors.mapping(SignatureService::toResponse, Collectors.toList())));
    }

    private static FileContentService.SignatureResponse toResponse(Signature s) {
        return new FileContentService.SignatureResponse(s.getSigner().getName(), s.getSignedAt(), s.isValid(), s.getSha256());
    }
}
