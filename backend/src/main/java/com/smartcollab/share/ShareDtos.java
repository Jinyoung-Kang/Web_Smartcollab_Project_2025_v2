package com.smartcollab.share;

import com.smartcollab.global.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ShareDtos {

    private ShareDtos() {
    }

    public record CreateRequest(
            @Size(min = 4, max = 72, message = "비밀번호는 4~72자입니다.")
            @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트 이하여야 합니다 (한글은 한 글자에 3바이트).")
            String password,
            @Min(value = 1, message = "유효 기간은 1시간 이상입니다.") @Max(value = 24 * 30, message = "유효 기간은 최대 30일입니다.")
            Integer expiresInHours,
            @Min(value = 1, message = "다운로드 횟수는 1회 이상입니다.") @Max(value = 1000, message = "다운로드 횟수는 최대 1000회입니다.")
            Integer downloadLimit) {
    }

    public record LinkResponse(Long id, String token, String path, boolean passwordProtected, Instant expiresAt,
                               Integer downloadLimit, int downloadCount, Instant createdAt, boolean active) {
        public static LinkResponse of(ShareLink l, Instant now) {
            return new LinkResponse(l.getId(), l.getToken(), "/share/" + l.getToken(), l.requiresPassword(),
                    l.getExpiresAt(), l.getDownloadLimit(), l.getDownloadCount(), l.getCreatedAt(),
                    !l.isExpired(now) && !l.isExhausted());
        }
    }

    /** 공개 페이지에 보여 줄 정보 (로그인 불필요) */
    public record PublicInfo(String fileName, long size, boolean passwordProtected, Instant expiresAt,
                             Integer remainingDownloads) {
    }

    public record UnlockRequest(String password) {
    }

    public record UnlockResponse(String grant) {
    }
}
