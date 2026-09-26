package com.smartcollab.global.error;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
}
