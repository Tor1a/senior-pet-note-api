package com.oraegyeot.seniorpet.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** 인증 API 요청·응답 형식. 웹·앱과 맞춘 계약이므로 필드명을 바꾸지 말 것(README.md "API"). */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/auth/signup 요청. 비밀번호 최대 72바이트는 BCrypt 한계. */
    public record SignupRequest(
            @NotBlank(message = "이메일을 입력하세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 254, message = "이메일이 너무 깁니다.")
            String email,
            @NotBlank(message = "비밀번호를 입력하세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
            String password) {
    }

    /** POST /api/auth/login 요청. */
    public record LoginRequest(
            @NotBlank(message = "이메일을 입력하세요.") String email,
            @NotBlank(message = "비밀번호를 입력하세요.") String password) {
    }

    /** {id, email} — GET /api/me 응답이자 로그인 응답의 user 필드. */
    public record UserResponse(UUID id, String email) {
    }

    /** {accessToken, user:{id, email}} — signup(201), login(200) 응답. */
    public record AuthResponse(String accessToken, UserResponse user) {
    }
}
