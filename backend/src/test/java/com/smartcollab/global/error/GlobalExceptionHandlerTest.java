package com.smartcollab.global.error;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("[SEC-08] 무결성 위반 로그에는 제약 이름만 남기고 입력값(이메일 등)은 남기지 않는다")
    void integrityViolationLogOmitsValues(CapturedOutput output) {
        String mysqlMessage = "Duplicate entry 'someone@example.com' for key 'users.uk_users_email'";   // MySQL 1062 형식
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement [" + mysqlMessage + "]",
                        new SQLIntegrityConstraintViolationException(mysqlMessage, "23000", 1062), "users.uk_users_email"));

        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler().handleIntegrity(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getDetail()).doesNotContain("someone@example.com");
        assertThat(output).contains("users.uk_users_email").doesNotContain("someone@example.com");
    }

    @Test
    @DisplayName("[IMP-04] DB 연결을 얻지 못하면 500 이 아니라 503 SERVICE_UNAVAILABLE 이고, 스택 없이 한 줄만 남긴다")
    void databaseUnavailableIs503(CapturedOutput output) {
        CannotCreateTransactionException ex = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                        new java.sql.SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms")));

        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler().handleUnavailable(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(output).contains("Database unavailable").doesNotContain("\tat org.");
    }

    @Test
    @DisplayName("[IMP-08] 쿼리 시간 제한에 걸리면 503 SERVICE_UNAVAILABLE")
    void queryTimeoutIs503() {
        ResponseEntity<ProblemDetail> response = new GlobalExceptionHandler()
                .handleUnavailable(new QueryTimeoutException("Statement cancelled due to timeout"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
    }
}
