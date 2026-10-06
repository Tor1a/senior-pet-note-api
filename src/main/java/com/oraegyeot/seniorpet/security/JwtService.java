package com.oraegyeot.seniorpet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * JWT 발급·검증 (HS256).
 * - subject = 사용자 id(UUID), email 클레임 포함
 * - 만료는 app.jwt.expiration (기본 7일). refresh 토큰은 MVP 범위 밖.
 */
@Service
public class JwtService {

    /** HS256 은 256비트(32바이트) 이상의 키가 필요하다. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration expiration;
    private final Clock clock = Clock.systemUTC();

    public JwtService(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "환경변수 JWT_SECRET 이 없거나 너무 짧습니다(" + MIN_SECRET_BYTES + "바이트 이상 필요).");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = props.expiration() != null ? props.expiration() : Duration.ofDays(7);
    }

    public String issue(UUID userId, String email) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 서명·만료가 유효하면 사용자 id 를 돌려준다. 그 외(위조, 만료, 형식 오류)는 빈 값. */
    public Optional<UUID> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
