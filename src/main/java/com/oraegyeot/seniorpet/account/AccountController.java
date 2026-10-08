package com.oraegyeot.seniorpet.account;

import com.oraegyeot.seniorpet.account.AccountDtos.ChangePasswordRequest;
import com.oraegyeot.seniorpet.account.AccountDtos.TokenResponse;
import com.oraegyeot.seniorpet.account.AccountDtos.WithdrawRequest;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 계정 관리 API(비밀번호 변경·회원 탈퇴). 계약은 docs/api-account.md. */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PutMapping("/api/me/password")
    public TokenResponse changePassword(@CurrentUserId UUID userId, @Valid @RequestBody ChangePasswordRequest req) {
        return new TokenResponse(accountService.changePassword(userId, req.currentPassword(), req.newPassword()));
    }

    @PostMapping("/api/me/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@CurrentUserId UUID userId, @Valid @RequestBody WithdrawRequest req) {
        accountService.withdraw(userId, req.password());
    }
}
