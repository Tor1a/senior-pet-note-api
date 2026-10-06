package com.oraegyeot.seniorpet.recorddate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/**
 * 기록 날짜(record_date) 계산 — 서버 안에서 이 클래스 한 곳에서만 계산한다.
 *
 * 규칙(docs/decisions/2026-10-06-MVP-세부-결정.md 결정 3, docs/api-today.md 0-2):
 * Asia/Seoul 기준 00:00~03:59 는 전날, 04:00 부터 당일.
 * 투약 체크(med_logs), 일일 기록(daily_logs), "오늘" 화면 날짜가 모두 이 규칙을 쓴다.
 * 대표가 기준을 바꾸면 아래 상수만 고치면 된다.
 */
@Component
public class RecordDateCalculator {

    /** 기준 시간대 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 이 시각 전(00:00~03:59)은 전날 기록으로 본다 */
    public static final LocalTime CUTOFF = LocalTime.of(4, 0);

    /** "오늘" 화면에 내려주는 안내 문구 */
    public static final String CUTOFF_NOTICE = "새벽 4시 전 투약은 전날 기록으로 저장돼요";

    private final Clock clock;

    public RecordDateCalculator(Clock clock) {
        this.clock = clock;
    }

    /** 지금 시각 */
    public Instant now() {
        return clock.instant();
    }

    /** 지금 시각 기준 기록 날짜 */
    public LocalDate currentRecordDate() {
        return recordDateOf(clock.instant());
    }

    /** 주어진 시각의 기록 날짜 */
    public static LocalDate recordDateOf(Instant instant) {
        ZonedDateTime local = instant.atZone(ZONE);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(CUTOFF) ? date.minusDays(1) : date;
    }
}
