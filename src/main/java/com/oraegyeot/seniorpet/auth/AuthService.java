package com.oraegyeot.seniorpet.auth;

import com.oraegyeot.seniorpet.auth.AuthDtos.AuthResponse;
import com.oraegyeot.seniorpet.auth.AuthDtos.UserResponse;
import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.security.JwtService;
import com.oraegyeot.seniorpet.user.User;
import com.oraegyeot.seniorpet.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입·로그인·내 정보. */
@Service
public class AuthService {

    /** 존재하지 않는 이메일로 로그인할 때도 BCrypt 비교를 해서 응답 시간 차이로 가입 여부가 드러나지 않게 한다. */
    private final String dummyHash;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("timing-dummy-password");
    }

    @Transactional
    public AuthResponse signup(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }
        User user;
        try {
            user = userRepository.saveAndFlush(new User(email, passwordEncoder.encode(password)));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 이메일로 가입한 경우(unique 제약 위반)
            throw emailTaken();
        }
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String rawEmail, String password) {
        Optional<User> found = userRepository.findByEmail(normalizeEmail(rawEmail));
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        // 72바이트를 넘는 입력은 가입할 수 없었던 값이라 일치할 수 없다(BCrypt 예외 → 500 방지). 응답 시간은 맞추려고 더미 비교를 한다.
        boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > 72;
        boolean matches = passwordEncoder.matches(tooLong ? "timing-dummy-password" : password, tooLong ? dummyHash : hash)
                && !tooLong;
        if (found.isEmpty() || !matches) {
            // 이메일이 없는지, 비밀번호가 틀렸는지 구분하지 않는다
            throw ApiException.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return toAuthResponse(found.get());
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> new UserResponse(u.getId(), u.getEmail()))
                .orElseThrow(() -> ApiException.unauthorized("로그인이 필요합니다."));
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.issue(user.getId(), user.getEmail(), user.getTokenVersion());
        return new AuthResponse(token, new UserResponse(user.getId(), user.getEmail()));
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입된 이메일입니다.");
    }
}
