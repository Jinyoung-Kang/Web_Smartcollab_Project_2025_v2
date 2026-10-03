package com.smartcollab.auth;

import com.smartcollab.global.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    /** 아이디 형식 — 가입과 로그인이 함께 씁니다(로그인은 형식이 다르면 사용자 조회·시도 기록 없이 실패) [S-02·S-03] */
    public static final String USERNAME_REGEX = "^[A-Za-z0-9_.-]{4,20}$";

    private AuthDtos() {
    }

    public record SignUpRequest(
            @NotBlank(message = "아이디를 입력하세요.")
            @Pattern(regexp = USERNAME_REGEX, message = "아이디는 영문·숫자·_ . - 조합 4~20자입니다.")
            String username,

            @NotBlank(message = "비밀번호를 입력하세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8~72자입니다.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호에는 영문과 숫자가 모두 들어가야 합니다.")
            @MaxUtf8Bytes(value = 72, message = "비밀번호는 72바이트 이하여야 합니다 (한글은 한 글자에 3바이트).")
            String password,

            @NotBlank(message = "비밀번호 확인을 입력하세요.")
            String passwordConfirm,

            @NotBlank(message = "이름을 입력하세요.")
            @Size(max = 50, message = "이름은 50자 이하입니다.")
            String name,

            @Email(message = "올바른 이메일 형식이 아닙니다.")
            @Size(max = 100, message = "이메일은 100자 이하입니다.")
            String email
    ) {
    }

    /** 길이 상한은 비정상 입력을 서비스 전에 막기 위한 것입니다(요청 본문 6MB 까지 아이디로 들어와 제한기 키로 쌓이던 문제) [S-02]. */
    public record LoginRequest(
            @NotBlank(message = "아이디를 입력하세요.") @Size(max = 50, message = "아이디 또는 비밀번호가 올바르지 않습니다.") String username,
            @NotBlank(message = "비밀번호를 입력하세요.") @Size(max = 200, message = "아이디 또는 비밀번호가 올바르지 않습니다.") String password
    ) {
    }

    public record MeResponse(Long id, String username, String name, String email, Long rootFolderId,
                             Instant createdAt) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
