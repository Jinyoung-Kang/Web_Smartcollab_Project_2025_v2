package com.smartcollab.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignUpRequest(
            @NotBlank(message = "아이디를 입력하세요.")
            @Pattern(regexp = "^[A-Za-z0-9_.-]{4,20}$", message = "아이디는 영문·숫자·_ . - 조합 4~20자입니다.")
            String username,

            @NotBlank(message = "비밀번호를 입력하세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8~72자입니다.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호에는 영문과 숫자가 모두 들어가야 합니다.")
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

    public record LoginRequest(
            @NotBlank(message = "아이디를 입력하세요.") String username,
            @NotBlank(message = "비밀번호를 입력하세요.") String password
    ) {
    }

    public record MeResponse(Long id, String username, String name, String email, Long rootFolderId,
                             Instant createdAt) {
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
