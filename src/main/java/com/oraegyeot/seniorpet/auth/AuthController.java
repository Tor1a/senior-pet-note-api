package com.oraegyeot.seniorpet.auth;

import com.oraegyeot.seniorpet.auth.AuthDtos.AuthResponse;
import com.oraegyeot.seniorpet.auth.AuthDtos.LoginRequest;
import com.oraegyeot.seniorpet.auth.AuthDtos.SignupRequest;
import com.oraegyeot.seniorpet.auth.AuthDtos.UserResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. 계약은 README.md "API" 참고. */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest req) {
        return authService.signup(req.email(), req.password());
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.email(), req.password());
    }

    @GetMapping("/api/me")
    public UserResponse me(@CurrentUserId UUID userId) {
        return authService.me(userId);
    }
}
