package com.smartcollab.share;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.file.FileEntity;
import com.smartcollab.file.FileRepository;
import com.smartcollab.file.FileService;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import com.smartcollab.global.security.SlidingWindowRateLimiter;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final FileRepository files;
    private final UserRepository users;
    private final AccessPolicy accessPolicy;
    private final PasswordEncoder passwordEncoder;
    private final DownloadGrantSigner grants;
    private final SlidingWindowRateLimiter rateLimiter;
    private final AppProperties props;

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
        FileEntity file = files.findWithFolder(link.getFile().getId()).orElseThrow(() -> ApiException.notFound("파일"));
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
    @Transactional(readOnly = true)
    public String unlock(String token, String password, String clientIp) {
        ShareLink link = usable(token);
        if (!link.requiresPassword()) {
            return grants.issue(token);
        }
        if (!rateLimiter.tryAcquire("share:" + token + ":" + clientIp, props.rateLimit().sharePasswordPer10Minutes(),
                Duration.ofMinutes(10))
                || !rateLimiter.tryAcquire("share-link:" + token, props.rateLimit().sharePasswordPerLinkPer10Minutes(),
                Duration.ofMinutes(10))) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "비밀번호 입력 시도가 너무 많습니다. 10분 뒤 다시 시도하세요.");
        }
        if (!StringUtils.hasText(password) || !passwordEncoder.matches(password, link.getPasswordHash())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호가 일치하지 않습니다.");
        }
        return grants.issue(token);
    }

    /**
     * 다운로드 1회를 소비하고 내려받을 파일 정보를 돌려줍니다. 횟수 차감은 조건부 UPDATE 로 원자적으로 처리합니다.
     */
    @Transactional
    public FileService.DownloadTarget consume(String token, String grant) {
        ShareLink link = usable(token);
        if (link.requiresPassword() && !grants.verify(token, grant)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "비밀번호 확인이 필요하거나 허가가 만료되었습니다. 다시 시도하세요.");
        }
        if (links.tryConsumeDownload(link.getId()) == 0) {
            throw new ApiException(ErrorCode.LINK_EXPIRED, "다운로드 가능 횟수를 모두 사용했습니다.");
        }
        FileEntity file = link.getFile();
        return new FileService.DownloadTarget(file.getName(), file.getActiveVersion().getStoredPath(),
                file.getActiveVersion().getSize());
    }

    /** 존재하고, 만료·소진되지 않았고, 파일이 휴지통에 있지 않은 링크만 사용할 수 있습니다. */
    private ShareLink usable(String token) {
        ShareLink link = links.findByToken(token).orElseThrow(() -> ApiException.notFound("공유 링크"));
        if (link.isExpired(Instant.now()) || link.isExhausted() || link.getFile().isDeleted()) {
            throw new ApiException(ErrorCode.LINK_EXPIRED);
        }
        return link;
    }

    private FileEntity activeFile(Long fileId) {
        FileEntity file = files.findWithFolder(fileId).orElseThrow(() -> ApiException.notFound("파일"));
        if (file.isDeleted()) {
            throw ApiException.notFound("파일");
        }
        return file;
    }

    /** 192bit 무작위 토큰 (URL-safe Base64, 32자) */
    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
