package com.smartcollab.global.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 컨트롤러 밖(보안 필터·요청 필터)에서도 오류를 {@link GlobalExceptionHandler} 와 같은 ProblemDetail(JSON) 형식으로 씁니다.
 */
public final class ProblemWriter {

    private ProblemWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code) throws IOException {
        write(response, mapper, code, code.defaultMessage());
    }

    public static void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.body(code, message));
    }
}
