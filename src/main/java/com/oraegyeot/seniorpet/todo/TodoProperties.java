package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.todo.* 설정(yml 전용, 보호자가 바꾸는 값이 아니다).
 * dayBeforeTime = 하루 전 알림 시각(기본 20:00), dueDayTime = 당일 알림 시각(기본 09:00). app.zone 현지 시각.
 * 기록 날짜 경계(RecordDateCalculator.CUTOFF, 04:00)보다 이르면 서버가 시작되지 않는다
 * (그 시각은 기록 날짜상 전날이라 하루 늦게 울리는 것을 막는다).
 */
@ConfigurationProperties("app.todo")
public record TodoProperties(LocalTime dayBeforeTime, LocalTime dueDayTime) {

    public static final LocalTime DEFAULT_DAY_BEFORE_TIME = LocalTime.of(20, 0);
    public static final LocalTime DEFAULT_DUE_DAY_TIME = LocalTime.of(9, 0);

    public TodoProperties {
        if (dayBeforeTime == null) {
            dayBeforeTime = DEFAULT_DAY_BEFORE_TIME;
        }
        if (dueDayTime == null) {
            dueDayTime = DEFAULT_DUE_DAY_TIME;
        }
        requireAfterCutoff("app.todo.day-before-time", dayBeforeTime);
        requireAfterCutoff("app.todo.due-day-time", dueDayTime);
    }

    private static void requireAfterCutoff(String name, LocalTime time) {
        if (time.isBefore(RecordDateCalculator.CUTOFF)) {
            throw new IllegalArgumentException(name + " 은 " + RecordDateCalculator.CUTOFF + " 이후여야 합니다: " + time);
        }
    }
}
