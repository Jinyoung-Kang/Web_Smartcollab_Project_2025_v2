package com.smartcollab.global.error;

import java.io.IOException;

/**
 * 요청 본문이 한도를 넘었습니다 [SEC-10]. 본문을 읽는 중에 던져지므로 입출력 예외로 둡니다.
 */
public class RequestBodyTooLargeException extends IOException {

    public static final String MESSAGE = "요청 본문이 너무 큽니다.";

    public RequestBodyTooLargeException() {
        super(MESSAGE);
    }

    /** 예외의 원인 사슬에 이 예외가 있는지 (Spring MVC 는 본문 읽기 오류를 다른 예외로 감쌉니다). */
    public static boolean isCauseOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestBodyTooLargeException) return true;
        }
        return false;
    }
}
