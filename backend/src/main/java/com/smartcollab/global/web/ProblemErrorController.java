package com.smartcollab.global.web;

import com.smartcollab.global.error.ProblemWriter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 컨트롤러 밖에서 끝나 /error 로 넘어온 요청의 오류 본문 [QA-08]. Boot 기본(BasicErrorController)은
 * {@code timestamp·status·error·path} JSON 이라, 보안 방화벽이 거절한 요청({@code //}·{@code ;})이 다른 오류와 다른 형식이었습니다.
 * 컨트롤러 안의 오류는 GlobalExceptionHandler 가, 라우팅 전 거절은 {@link ProblemErrorReportValve} 가 처리합니다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class ProblemErrorController implements ErrorController {

    private final ObjectMapper mapper;

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 오류 처리로 넘어온 게 아니라 /error 를 직접 부른 요청은 없는 주소처럼 답합니다.
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 404;
        // 추적 필터가 원래 요청에 붙인 ID (오류 처리 단계에서는 로그 문맥이 이미 비어 있음)
        ProblemWriter.writeStatus(response, mapper, status, response.getHeader(RequestTraceFilter.HEADER));
    }
}
