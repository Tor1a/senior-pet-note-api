package com.oraegyeot.seniorpet.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.jwt.* 설정. secret 은 환경변수 JWT_SECRET 으로 받는다. */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration expiration) {
}
