package com.smartcollab.global.error;

/**
 * 바깥 의존성(파일 저장소 등)이 잠시 응답하지 않음 — 다시 시도하면 될 수 있습니다. 응답은 503 + Retry-After 입니다 [M-3].
 * 저장소 어댑터처럼 도메인을 모르는 쪽이 SDK 예외를 이것으로 바꿔 올립니다.
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
