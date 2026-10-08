package com.oraegyeot.seniorpet.common;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.* 공통 설정.
 * zone = 서비스 기준 시간대(환경변수 APP_ZONE, 기본 Asia/Seoul). 기록 날짜(새벽 4시 규칙)·알림 발송 시각·지난 기록 기간 계산이 이 시간대를 쓴다.
 * 잘못된 값이면 서버가 시작되지 않는다. (JVM·DB 시간대와는 별개다. JVM 은 항상 UTC 로 고정한다: SeniorPetApplication)
 */
@ConfigurationProperties("app")
public record AppProperties(String zone) {

    public static final String DEFAULT_ZONE = "Asia/Seoul";

    public AppProperties {
        if (zone == null || zone.isBlank()) {
            zone = DEFAULT_ZONE;
        }
        zone = zone.trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("app.zone(APP_ZONE) 값이 올바른 시간대가 아닙니다: '" + zone
                    + "' (예: Asia/Seoul, UTC, America/New_York)", e);
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
