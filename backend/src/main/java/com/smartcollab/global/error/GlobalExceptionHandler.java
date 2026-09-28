package com.smartcollab.global.error;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모든 오류를 RFC 9457 ProblemDetail(JSON)로 통일합니다.
 * v1 은 SecurityException·RuntimeException 을 처리하지 않아 권한 오류가 500 으로 노출되었습니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 요청 추적 ID 의 MDC 키이자 오류 본문 속성 이름 [ARC-02] */
    public static final String REQUEST_ID = "requestId";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return problem(e.code(), e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return problem(ErrorCode.EDIT_CONFLICT, ErrorCode.EDIT_CONFLICT.defaultMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        // DB 오류 메시지에는 중복된 값(이메일 등)이 그대로 들어 있으므로 제약 이름만 기록합니다 [SEC-08].
        log.info("Data integrity violation: constraint={}", constraintName(e));
        return problem(ErrorCode.CONFLICT, "이미 존재하거나 다른 데이터가 참조하고 있어 처리할 수 없습니다.");
    }

    private static String constraintName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        return "unknown";
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException e) {
        return problem(ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_CREDENTIALS.defaultMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e) {
        return problem(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        String first = errors.values().stream().findFirst().orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        ProblemDetail body = body(ErrorCode.INVALID_REQUEST, first);
        body.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        String message = ex.getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status()).body(body(ErrorCode.INVALID_REQUEST, message));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        // 길이를 알리지 않은 본문이 읽는 도중 한도를 넘은 경우 [SEC-10]
        if (RequestBodyTooLargeException.isCauseOf(ex)) {
            return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.status())
                    .body(body(ErrorCode.PAYLOAD_TOO_LARGE, RequestBodyTooLargeException.MESSAGE));
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.status())
                .body(body(ErrorCode.PAYLOAD_TOO_LARGE, "업로드 가능한 최대 크기를 초과했습니다."));
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        // Spring MVC 기본 예외(404, 405, 415 …)에도 code 속성을 붙여 형식을 맞춥니다.
        if (body instanceof ProblemDetail pd && pd.getProperties() == null) {
            pd.setProperty("code", statusCode.value() == 404 ? ErrorCode.NOT_FOUND.name() : ErrorCode.INVALID_REQUEST.name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(body(code, message));
    }

    public static ProblemDetail body(ErrorCode code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), message);
        pd.setTitle(code.status().getReasonPhrase());
        pd.setProperty("code", code.name());
        // 사용자가 알려 준 오류를 서버 로그에서 바로 찾을 수 있도록 요청 추적 ID 를 함께 돌려줍니다.
        String requestId = MDC.get(REQUEST_ID);
        if (requestId != null) {
            pd.setProperty(REQUEST_ID, requestId);
        }
        return pd;
    }
}
