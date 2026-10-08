package com.oraegyeot.seniorpet.account;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.pet.photo.PhotoStorage;
import com.oraegyeot.seniorpet.security.JwtService;
import com.oraegyeot.seniorpet.user.User;
import com.oraegyeot.seniorpet.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 변경·회원 탈퇴. 이메일·비밀번호는 로그에 남기지 않는다.
 * 클래스에 @Transactional 을 걸지 않는다: BCrypt 비교(느림)는 트랜잭션 밖에서 하고,
 * DB 쓰기만 TransactionTemplate 으로 짧게 묶는다.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** BCrypt 는 비밀번호를 UTF-8 72바이트까지만 쓴다. */
    static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordAttemptLimiter limiter;
    private final PhotoStorage photoStorage;
    private final TransactionTemplate tx;

    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                          PasswordAttemptLimiter limiter, PhotoStorage photoStorage,
                          PlatformTransactionManager txManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.limiter = limiter;
        this.photoStorage = photoStorage;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 새 토큰을 돌려준다(token_version 이 올라가므로 이전 토큰은 모두 무효). */
    public String changePassword(UUID userId, String currentPassword, String newPassword) {
        if (utf8Length(newPassword) > BCRYPT_MAX_BYTES) {
            throw ApiException.validation("비밀번호는 영문·숫자 기준 72자, 한글은 24자 이하여야 합니다.");
        }
        User user = verifyPassword(userId, currentPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.validation("현재 비밀번호와 다른 비밀번호를 입력하세요.");
        }
        String newHash = passwordEncoder.encode(newPassword);
        int verified = user.getTokenVersion();
        return tx.execute(status -> {
            // 검증 시점의 token_version 과 같을 때만 갱신: 같은 현재 비밀번호의 변경이 겹쳐도 한쪽만 성공한다
            int updated = userRepository.changePasswordIfVersion(userId, newHash, verified);
            if (updated == 0) {
                if (!userRepository.existsById(userId)) {
                    throw loginRequired();
                }
                throw ApiException.passwordChangeConflict();
            }
            return jwtService.issue(userId, user.getEmail(), verified + 1);
        });
    }

    public void withdraw(UUID userId, String password) {
        verifyPassword(userId, password);
        // 단일 트랜잭션: users 삭제 → 자식 테이블은 DB cascade. 실패하면 전부 롤백
        Integer deleted = tx.execute(status -> userRepository.deleteUserById(userId));
        if (deleted == null || deleted == 0) {
            throw loginRequired(); // 경합으로 이미 삭제됨
        }
        limiter.reset(userId);
        // 커밋 뒤에 파일 삭제. 실패해도 탈퇴는 성공(deleteAllOf 가 예외를 던지지 않고 로그만 남긴다)
        try {
            photoStorage.deleteAllOf(userId);
        } catch (RuntimeException e) {
            log.warn("탈퇴 후 사진 폴더 정리 실패(무시): userId={}, 원인={}", userId, e.getClass().getSimpleName());
        }
    }

    /** 속도 제한 슬롯 예약 → 사용자 조회 → 비밀번호 비교. 틀리면 실패를 세고 400, 맞으면 카운터 초기화. */
    private User verifyPassword(UUID userId, String rawPassword) {
        // 비교 전에 시도 슬롯을 먼저 예약한다(동시 요청이 비교 뒤 기록 사이로 한도를 넘지 못하게). 성공하면 초기화
        limiter.acquire(userId);
        User user = userRepository.findById(userId).orElseThrow(() -> {
            limiter.reset(userId);
            return loginRequired();
        });
        // 72바이트를 넘는 입력은 가입할 수 없었던 값이므로 일치할 수 없다(BCrypt 가 예외를 던지는 것도 막는다)
        boolean matches = utf8Length(rawPassword) <= BCRYPT_MAX_BYTES
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (!matches) {
            throw ApiException.currentPasswordMismatch();
        }
        limiter.reset(userId);
        return user;
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static ApiException loginRequired() {
        return ApiException.unauthorized("로그인이 필요합니다.");
    }
}
