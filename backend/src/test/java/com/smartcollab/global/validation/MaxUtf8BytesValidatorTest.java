package com.smartcollab.global.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesValidatorTest {

    private final MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();

    @MaxUtf8Bytes(72)
    private String annotated;

    @Test
    void countsUtf8BytesNotCharacters() throws Exception {
        validator.initialize(getClass().getDeclaredField("annotated").getAnnotation(MaxUtf8Bytes.class));

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("a".repeat(72), null)).isTrue();          // 72바이트
        assertThat(validator.isValid("a".repeat(73), null)).isFalse();         // 73바이트
        assertThat(validator.isValid("가".repeat(24), null)).isTrue();         // 24자 = 72바이트
        assertThat(validator.isValid("가".repeat(24) + "a", null)).isFalse();  // 25자 = 73바이트
    }
}
