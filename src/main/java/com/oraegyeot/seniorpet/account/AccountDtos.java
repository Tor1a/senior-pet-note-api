package com.oraegyeot.seniorpet.account;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 계정 관리 API 요청·응답 형식(docs/api-account.md). 필드명을 바꾸지 말 것. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** PUT /api/me/password 요청. 새 비밀번호의 72바이트 한도는 서비스에서 바이트 기준으로 검증한다. */
    public record ChangePasswordRequest(
            @NotBlank(message = "현재 비밀번호를 입력하세요.") String currentPassword,
            @NotBlank(message = "새 비밀번호를 입력하세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
            String newPassword) {
    }

    /** PUT /api/me/password 응답: 새 토큰(이 기기는 이 토큰으로 바꿔 쓴다). */
    public record TokenResponse(String accessToken) {
    }

    /** POST /api/me/withdraw 요청. confirm 은 "삭제되는 내용을 확인했어요" 체크박스 값(true 여야 한다). */
    public record WithdrawRequest(
            @NotBlank(message = "비밀번호를 입력하세요.") String password,
            @NotNull(message = "탈퇴 확인이 필요합니다.")
            @AssertTrue(message = "탈퇴 확인이 필요합니다.")
            Boolean confirm) {
    }
}
