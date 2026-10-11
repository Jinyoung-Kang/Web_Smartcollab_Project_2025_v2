package com.smartcollab.share;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileService;
import com.smartcollab.file.FileService;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.SecurityEventLog;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShareService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository links;
    private final FileService fileService;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final PasswordEncoder passwordEncoder;
    private final DownloadGrantSigner grants;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AppProperties props;
    private final TransactionRunner tx;

    @Transactional
    public ShareDtos.LinkResponse create(Long fileId, ShareDtos.CreateRequest req, Long userId) {
        FileEntity file = activeFile(fileId);
        accessPolicy.requireShare(file, userId);
        Instant expiresAt = req.expiresInHours() == null ? null : Instant.now().plus(Duration.ofHours(req.expiresInHours()));
        String hash = StringUtils.hasText(req.password()) ? passwordEncoder.encode(req.password()) : null;
        ShareLink link = links.save(new ShareLink(file, users.getReferenceById(userId), newToken(), hash, expiresAt,
                req.downloadLimit()));
        return ShareDtos.LinkResponse.of(link, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ShareDtos.LinkResponse> list(Long fileId, Long userId) {
        FileEntity file = activeFile(fileId);
        accessPolicy.requireShare(file, userId);
        Instant now = Instant.now();
        return links.findByFile(fileId).stream().map(l -> ShareDtos.LinkResponse.of(l, now)).toList();
    }

    @Transactional
    public void revoke(Long linkId, Long userId) {
        ShareLink link = links.findById(linkId).orElseThrow(() -> ApiException.notFound("공유 링크"));
        FileEntity file = link.getFile();   // 링크 해제는 휴지통의 파일이어도 할 수 있습니다
        accessPolicy.requireShare(file, userId);
        links.delete(link);
    }

    // ---- 공개(로그인 불필요) ----

    @Transactional(readOnly = true)
    public ShareDtos.PublicInfo info(String token) {
        ShareLink link = usable(token);
        Integer remaining = link.getDownloadLimit() == null ? null : link.getDownloadLimit() - link.getDownloadCount();
        return new ShareDtos.PublicInfo(link.getFile().getName(), link.getFile().getSize(), link.requiresPassword(),
                link.getExpiresAt(), remaining);
    }

    /**
     * 비밀번호 확인 → 5분짜리 다운로드 허가 발급.
     * 시도 횟수는 링크+IP 단위와 링크 단위(여러 IP 에서 오는 대입 공격 대비)로 10분마다 제한합니다.
     */
    public String unlock(String token, String password, String clientIp) {
        // 링크 확인만 짧은 읽기 트랜잭션에서 하고, bcrypt 비교 동안 DB 커넥션을 쥐지 않습니다 [M-2]
        PasswordCheck link = tx.readOnly(() -> {
            ShareLink found = usable(token);
            return new PasswordCheck(found.getId(), found.requiresPassword(), found.getPasswordHash());
        });
        if (!link.requiresPassword()) {
            return grants.issue(token);
        }
        if (!rateLimiter.tryAcquire("share:" + token + ":" + clientIp, props.rateLimit().sharePasswordPer10Minutes(),
                Duration.ofMinutes(10))
                || !rateLimiter.tryAcquire("share-link:" + token, props.rateLimit().sharePasswordPerLinkPer10Minutes(),
                Duration.ofMinutes(10))) {
            SecurityEventLog.rateLimited("share-password", clientIp);
            throw new ApiException(ErrorCode.RATE_LIMITED, "비밀번호 입력 시도가 너무 많습니다. 10분 뒤 다시 시도하세요.");
        }
        if (!StringUtils.hasText(password) || !passwordEncoder.matches(password, link.passwordHash())) {
            SecurityEventLog.sharePasswordFailed(link.id(), clientIp);
            throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호가 일치하지 않습니다.");
        }
        return grants.issue(token);
    }

    /**
     * 공유 링크 다운로드: 링크·허가 확인 → 저장소 열기 → 다운로드 1회 차감 [L-2]. 저장소를 열지 못하면 횟수를 쓰지 않습니다 —
     * 예전에는 차감을 먼저 커밋해, 저장소 오류에도 1회용 링크가 내려받지 못한 채 소진됐습니다.
     * 차감은 조건부 UPDATE 라 동시에 받아도 정해진 횟수만 성공하고, 그사이 링크가 해제·소진되면 연 스트림을 닫고 410 입니다.
     */
    public Download download(String token, String grant) {
        DownloadCheck check = tx.readOnly(() -> {
            ShareLink link = usable(token);
            if (link.requiresPassword() && !grants.verify(token, grant)) {
                throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호 확인이 필요하거나 허가가 만료되었습니다. 다시 시도하세요.");
            }
            FileEntity file = link.getFile();
            return new DownloadCheck(link.getId(), new FileService.DownloadTarget(file.getName(),
                    file.getActiveVersion().getStoredPath(), file.getActiveVersion().getSize()));
        });
        InputStream in = fileService.open(check.target());
        try {
            int consumed = tx.writeReadCommitted(() -> links.tryConsumeDownload(check.linkId()));
            if (consumed == 0) {
                throw new ApiException(ErrorCode.LINK_EXPIRED, "다운로드 가능 횟수를 모두 사용했습니다.");
            }
        } catch (RuntimeException e) {
            closeQuietly(in);
            throw e;
        }
        return new Download(check.target(), in);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 응답을 보내지 않으므로 닫기 실패는 무시
        }
    }

    /** 내려받을 파일과 열어 둔 내용 — 호출하는 쪽이 응답으로 흘려보낸 뒤 닫습니다 */
    public record Download(FileService.DownloadTarget target, InputStream stream) {
    }

    private record DownloadCheck(Long linkId, FileService.DownloadTarget target) {
    }

    /**
     * 존재하고, 만료·소진되지 않았고, 파일이 휴지통에 있지 않고, 만든 사람이 지금도 그 파일을 공유할 수 있는 링크만 사용할 수 있습니다.
     * 만든 사람이 팀에서 나가면 링크는 지우지 않고 동작만 멈춥니다 — 다시 권한이 생기면 다시 동작합니다 [S-09].
     */
    /** 비밀번호를 확인할 링크 — 트랜잭션 밖으로 가져가는 값만 */
    private record PasswordCheck(Long id, boolean requiresPassword, String passwordHash) {
    }

    private ShareLink usable(String token) {
        ShareLink link = links.findByToken(token).orElseThrow(() -> ApiException.notFound("공유 링크"));
        if (link.isExpired(Instant.now()) || link.isExhausted() || link.getFile().isInTrash()
                || !accessPolicy.canShare(link.getFile(), link.getOwner().getId())) {
            throw new ApiException(ErrorCode.LINK_EXPIRED);
        }
        return link;
    }

    private FileEntity activeFile(Long fileId) {
        return fileService.getActive(fileId);
    }

    /** 192bit 무작위 토큰 (URL-safe Base64, 32자) */
    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
