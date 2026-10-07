package com.oraegyeot.seniorpet.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * FCM 기기 토큰 (device_tokens 테이블). token 은 전역 unique.
 * 토큰 값은 API 응답에 다시 내보내지 않는다(DeviceTokenDtos.DeviceResponse 에 없음).
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String token;

    /** android | ios | web */
    @Column(nullable = false, columnDefinition = "text")
    private String platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected DeviceToken() {
        // JPA 용
    }

    public DeviceToken(UUID userId, String token, String platform, Instant now) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    /** 같은 토큰 재등록: 플랫폼·마지막 등록 시각 갱신 */
    public void touch(String platform, Instant now) {
        this.platform = platform;
        this.lastSeenAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public String getPlatform() {
        return platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
