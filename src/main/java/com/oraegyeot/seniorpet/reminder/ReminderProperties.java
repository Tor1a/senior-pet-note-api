package com.oraegyeot.seniorpet.reminder;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.reminder.* 설정.
 * schedulerEnabled = 1분 주기 발송 스케줄러 on/off(환경변수 REMINDER_SCHEDULER_ENABLED, 기본 true. 테스트는 false).
 * catchUp = 발송 시각이 지난 회차를 늦게라도 보내는 최대 지연(기본 PT10M). 그보다 오래된 회차는 버린다.
 */
@ConfigurationProperties("app.reminder")
public record ReminderProperties(boolean schedulerEnabled, Duration catchUp) {

    /** 창이 24시간 이상이면 같은 시각의 회차가 둘 생기므로 24시간 미만만 허용한다 */
    public ReminderProperties {
        if (catchUp != null && catchUp.compareTo(Duration.ofHours(24)) >= 0) {
            throw new IllegalArgumentException("app.reminder.catch-up 은 24시간 미만이어야 합니다: " + catchUp);
        }
    }

    public static final Duration DEFAULT_CATCH_UP = Duration.ofMinutes(10);

    public Duration catchUpOrDefault() {
        return (catchUp == null || catchUp.isNegative() || catchUp.isZero()) ? DEFAULT_CATCH_UP : catchUp;
    }
}
