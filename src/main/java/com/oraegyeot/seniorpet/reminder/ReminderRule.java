package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 알림 반복 규칙 판정(순수 로직, DB·시계 없음).
 * 판정 기준은 기록 날짜(record_date)다. 요일·간격·시작/종료일 모두 기록 날짜로 본다.
 * 회차(기록 날짜, 시각)의 실제 발송 시각은 RecordDateCalculator.slotInstant 가 정한다(04:00 전 시각은 다음 날).
 *
 * @param enabled      false 면 어떤 날도 해당하지 않는다
 * @param daysOfWeek   WEEKLY 일 때 요일(그 외 빈 집합)
 * @param intervalDays INTERVAL 일 때 N(그 외 null)
 * @param startDate    시작일(포함), INTERVAL 의 기준일
 * @param endDate      종료일(포함), null = 종료 없음
 */
public record ReminderRule(boolean enabled, RepeatType repeat, Set<DayOfWeek> daysOfWeek, Integer intervalDays,
                           LocalDate startDate, LocalDate endDate) {

    /** nextFireAt 을 계산하는 최대 범위(현재 기록 날짜로부터) */
    public static final int MAX_LOOKAHEAD_DAYS = 400;

    /** 기록 날짜 recordDate 의 회차에 알림을 보내는지 */
    public boolean matches(LocalDate recordDate) {
        if (!enabled || recordDate.isBefore(startDate) || (endDate != null && recordDate.isAfter(endDate))) {
            return false;
        }
        return switch (repeat) {
            case DAILY -> true;
            case WEEKLY -> daysOfWeek.contains(recordDate.getDayOfWeek());
            case INTERVAL -> ChronoUnit.DAYS.between(startDate, recordDate) % intervalDays == 0;
        };
    }

    /**
     * now 이후(초과) 첫 발송 예정 시각. 꺼져 있거나 종료일이 지났거나 {@value #MAX_LOOKAHEAD_DAYS}일 안에 없으면 null.
     * times = 약의 투약 시각(medications.times).
     */
    public Instant nextFireAt(Instant now, List<LocalTime> times) {
        if (!enabled || times.isEmpty()) {
            return null;
        }
        LocalDate today = RecordDateCalculator.recordDateOf(now);
        LocalDate from = startDate.isAfter(today) ? startDate : today;
        LocalDate to = today.plusDays(MAX_LOOKAHEAD_DAYS);
        if (endDate != null && endDate.isBefore(to)) {
            to = endDate;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!matches(d)) {
                continue;
            }
            LocalDate day = d;
            Instant next = times.stream()
                    .map(t -> RecordDateCalculator.slotInstant(day, t))
                    .filter(at -> at.isAfter(now))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (next != null) {
                return next;
            }
        }
        return null;
    }
}
