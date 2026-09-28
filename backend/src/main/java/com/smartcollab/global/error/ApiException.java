package com.smartcollab.global.error;

/**
 * 비즈니스 규칙 위반을 표현하는 예외. {@link GlobalExceptionHandler} 가 RFC 9457 ProblemDetail 로 변환합니다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + objectParticle(what) + " 찾을 수 없습니다.");
    }

    /**
     * 목적격 조사 [UX-04]. 마지막 글자가 한글이면 받침이 있으면 "을", 없으면 "를".
     * 한글로 끝나지 않으면 읽는 법을 알 수 없어 "을(를)" 을 씁니다.
     */
    static String objectParticle(String word) {
        char last = word.isEmpty() ? 0 : word.charAt(word.length() - 1);
        if (last < '가' || last > '힣') {
            return "을(를)";
        }
        return (last - '가') % 28 == 0 ? "를" : "을";
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }
}
