package com.smartcollab.global.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 컨트롤러 밖(보안 필터·요청 필터)에서도 오류를 {@link GlobalExceptionHandler} 와 같은 ProblemDetail(JSON) 형식으로 씁니다.
 */
public final class ProblemWriter {

    private static final String MALFORMED_REQUEST = "요청 주소나 형식이 올바르지 않습니다.";

    private ProblemWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        write(response, mapper, code, code.defaultMessage());
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code, String message)
            throws IOException {
        write(response, mapper, code.status().value(), GlobalExceptionHandler.body(code, message));
    }

    /**
     * 상태 코드만 아는 곳의 오류 [QA-08] — Tomcat 이 라우팅 전에 거절한 요청과, 컨트롤러 밖에서 끝나 /error 로 넘어온 요청.
     * 400 은 대부분 해석할 수 없는 주소(%00·%2F·//·;)라 그렇게 안내합니다.
     */
    public static void writeStatus(HttpServletResponse response, ObjectMapper mapper, int status, String requestId)
            throws IOException {
        ErrorCode code = ErrorCode.forStatus(status);
        ProblemDetail body = GlobalExceptionHandler.body(code, status == 400 ? MALFORMED_REQUEST : code.defaultMessage());
        body.setStatus(status);
        HttpStatus resolved = HttpStatus.resolve(status);
        body.setTitle(resolved == null ? "Error" : resolved.getReasonPhrase());
        if (requestId != null) {
            body.setProperty(GlobalExceptionHandler.REQUEST_ID, requestId);
        }
        write(response, mapper, status, body);
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, int status, ProblemDetail body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
