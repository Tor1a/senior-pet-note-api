package com.oraegyeot.seniorpet.push;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** 기기 토큰 API 요청·응답 (docs/api-reminders.md 3절). */
public final class DeviceTokenDtos {

    private DeviceTokenDtos() {
    }

    /** PUT /api/devices 요청. userId 필드는 두지 않는다(로그인 사용자로 저장). */
    public record DeviceRequest(
            @NotNull(message = "기기 토큰을 입력하세요.")
            @Size(min = 1, max = 4096, message = "기기 토큰은 1~4096자여야 합니다.")
            // (?U) = UNICODE_CHARACTER_CLASS: 전각 공백·NBSP 도 공백으로 본다
            @Pattern(regexp = "(?U)^\\S+$", message = "기기 토큰에 공백을 넣을 수 없습니다.")
            String token,
            @NotNull(message = "플랫폼을 입력하세요.")
            @Pattern(regexp = "android|ios|web", message = "플랫폼은 android, ios, web 중 하나여야 합니다.")
            String platform) {
    }

    /** Device = {id, platform, createdAt, lastSeenAt} — 토큰 값은 다시 내보내지 않는다. */
    public record DeviceResponse(UUID id, String platform, Instant createdAt, Instant lastSeenAt) {

        public static DeviceResponse from(DeviceToken d) {
            return new DeviceResponse(d.getId(), d.getPlatform(), d.getCreatedAt(), d.getLastSeenAt());
        }
    }
}
