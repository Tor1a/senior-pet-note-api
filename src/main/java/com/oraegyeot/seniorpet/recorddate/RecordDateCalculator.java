package com.oraegyeot.seniorpet.recorddate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 기록 날짜(record_date) 계산 — 서버 안에서 이 클래스 한 곳에서만 계산한다.
 *
 * 규칙(docs/api-today.md 0-2):
 * Asia/Seoul 기준 00:00~03:59 는 전날, 04:00 부터 당일.
 * 투약 체크(med_logs), 일일 기록(daily_logs), "오늘" 화면 날짜, 투약 알림 발송 시각이 모두 이 규칙을 쓴다.
 * 대표가 기준을 바꾸면 아래 상수만 고치면 된다(CUTOFF 를 바꾸면 기록 날짜와 알림 발송 시각이 함께 바뀐다).
 */
@Component
public class RecordDateCalculator {

    /** 기준 시간대 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 이 시각 전(00:00~03:59)은 전날 기록으로 본다. 알림 발송 시각(slotInstant)도 이 값을 따른다. */
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

    /**
     * 투약 회차(기록 날짜 recordDate, 일정 시각 time)의 실제 시각.
     * time 이 CUTOFF 전이면 기록 날짜 다음 날의 그 시각, 아니면 기록 날짜 당일의 그 시각(서울 기준).
     * 예: (10-06, 08:00) → 10-06 08:00, (10-06, 02:00) → 10-07 02:00. recordDateOf(slotInstant(d, t)) == d 이다.
     */
    public static Instant slotInstant(LocalDate recordDate, LocalTime time) {
        LocalDate date = time.isBefore(CUTOFF) ? recordDate.plusDays(1) : recordDate;
        return date.atTime(time).atZone(ZONE).toInstant();
    }

    /** 분 단위 회차 1개: 기록 날짜 + 일정 시각(서울, HH:mm) + 실제 시각 */
    public record Slot(LocalDate recordDate, LocalTime time, Instant fireAt) {
    }

    /**
     * (fromExclusive, toInclusive] 구간 안의 모든 정각 분(초 0)을 회차로 바꿔 시간 순으로 돌려준다.
     * 알림 발송 작업이 "지금까지 늦게라도 보낼 회차"를 찾을 때 쓴다(자정·04시 경계를 넘는 구간도 처리).
     */
    public static List<Slot> minuteSlotsBetween(Instant fromExclusive, Instant toInclusive) {
        List<Slot> slots = new ArrayList<>();
        Instant t = fromExclusive.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        for (; !t.isAfter(toInclusive); t = t.plus(1, ChronoUnit.MINUTES)) {
            slots.add(new Slot(recordDateOf(t), t.atZone(ZONE).toLocalTime(), t));
        }
        return slots;
    }
}
