package com.smartcollab.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열의 UTF-8 바이트 길이 상한. {@code @Size} 는 글자 수를 세므로 한글처럼 한 글자가 3바이트인 입력을 막지 못합니다.
 * BCrypt 는 72바이트까지만 처리하고 넘으면 예외를 던지므로 비밀번호에 사용합니다 [BUG-01].
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "입력이 너무 깁니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
