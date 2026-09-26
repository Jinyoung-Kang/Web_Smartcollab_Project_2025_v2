package com.smartcollab.global.error;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드. 클라이언트는 HTTP 상태 대신 이 코드로 분기할 수 있습니다.
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 작업을 할 권한이 없습니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "보안 토큰이 없거나 만료되었습니다. 페이지를 새로고침한 뒤 다시 시도하세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 요청입니다."),
    EDIT_CONFLICT(HttpStatus.CONFLICT, "다른 사용자가 먼저 저장했습니다. 최신 내용을 확인한 뒤 다시 저장하세요."),
    LINK_EXPIRED(HttpStatus.GONE, "만료되었거나 더 이상 사용할 수 없는 링크입니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "파일이 너무 큽니다."),
    QUOTA_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "저장 공간이 부족합니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도하세요."),
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "이 기능은 현재 서버에서 설정되어 있지 않습니다."),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "외부 서비스 호출에 실패했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
