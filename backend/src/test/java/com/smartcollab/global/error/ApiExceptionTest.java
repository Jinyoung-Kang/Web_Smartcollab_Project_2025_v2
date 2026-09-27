package com.smartcollab.global.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    @DisplayName("[UX-04] '찾을 수 없음' 문구는 받침에 맞는 조사를 쓴다 (\"공유 링크을(를)\" → \"공유 링크를\")")
    void notFoundUsesMatchingParticle() {
        assertThat(ApiException.notFound("공유 링크").getMessage()).isEqualTo("공유 링크를 찾을 수 없습니다.");
        assertThat(ApiException.notFound("폴더").getMessage()).isEqualTo("폴더를 찾을 수 없습니다.");
        assertThat(ApiException.notFound("파일").getMessage()).isEqualTo("파일을 찾을 수 없습니다.");
        assertThat(ApiException.notFound("팀").getMessage()).isEqualTo("팀을 찾을 수 없습니다.");
        assertThat(ApiException.notFound("알림").getMessage()).isEqualTo("알림을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("한글로 끝나지 않으면 읽는 법을 알 수 없으므로 을(를)을 그대로 쓴다")
    void fallsBackForNonHangul() {
        assertThat(ApiException.notFound("API").getMessage()).isEqualTo("API을(를) 찾을 수 없습니다.");
    }
}
