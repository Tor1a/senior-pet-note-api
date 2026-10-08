package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 반복 규칙 경계값(QA 보강). ReminderRuleTest 에 없는 경계를 다룬다.
 * 2026-10-12 = 월요일.
 */
class ReminderRuleBoundaryTest {

    private static final RecordDateCalculator CALC =
            new RecordDateCalculator(java.time.Clock.systemUTC(), java.time.ZoneId.of("Asia/Seoul"));
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final List<LocalTime> EIGHT = List.of(LocalTime.of(8, 0));
    private static final List<LocalTime> TWO_AM = List.of(LocalTime.of(2, 0));

    private static Instant seoul(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+09:00").toInstant();
    }

    private static ReminderRule interval(int n, LocalDate start, LocalDate end) {
        return new ReminderRule(true, RepeatType.INTERVAL, Set.of(), n, start, end);
    }

    @Test
    void interval_최소값_2일() {
        ReminderRule r = interval(2, MON, null);
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(1))).isFalse();
        assertThat(r.matches(MON.plusDays(2))).isTrue();
        assertThat(r.matches(MON.plusDays(100))).isTrue();
        assertThat(r.matches(MON.plusDays(101))).isFalse();
    }

    @Test
    void interval_최대값_30일은_기준일과_30일_뒤만() {
        ReminderRule r = interval(30, MON, null);
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(29))).isFalse();
        assertThat(r.matches(MON.plusDays(30))).isTrue();
        assertThat(r.matches(MON.plusDays(31))).isFalse();
        assertThat(r.matches(MON.plusDays(60))).isTrue();
    }

    @Test
    void interval_기준일은_오래전_과거여도_간격이_유지된다() {
        // 2025-01-01 기준 7일 간격. 2026-10-12 까지 649일 = 7*92 + 5 → 2026-10-14 가 해당
        ReminderRule r = interval(7, LocalDate.of(2025, 1, 1), null);
        assertThat(r.matches(LocalDate.of(2026, 10, 12))).isFalse();
        assertThat(r.matches(LocalDate.of(2026, 10, 14))).isTrue();
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T09:00"), EIGHT)).isEqualTo(seoul("2026-10-14T08:00"));
    }

    @Test
    void interval_윤년_2월말을_넘어도_날짜_차이로_계산한다() {
        // 2028 은 윤년: 02-27 기준 2일 간격 → 02-29 해당, 03-01 아님, 03-02 해당
        ReminderRule r = interval(2, LocalDate.of(2028, 2, 27), null);
        assertThat(r.matches(LocalDate.of(2028, 2, 29))).isTrue();
        assertThat(r.matches(LocalDate.of(2028, 3, 1))).isFalse();
        assertThat(r.matches(LocalDate.of(2028, 3, 2))).isTrue();
    }

    @Test
    void interval_종료일이_간격에_맞지_않으면_마지막_회차는_그_이전() {
        ReminderRule r = interval(3, MON, MON.plusDays(4)); // 해당 날: MON, MON+3 (MON+6 은 종료 후)
        assertThat(r.matches(MON.plusDays(3))).isTrue();
        assertThat(r.matches(MON.plusDays(4))).isFalse();
        assertThat(r.matches(MON.plusDays(6))).isFalse();
        assertThat(r.nextFireAt(CALC, seoul("2026-10-15T09:00"), EIGHT)).isNull();
    }

    @Test
    void 시작일과_종료일이_같으면_그날_하루만() {
        ReminderRule r = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON, MON);
        assertThat(r.matches(MON.minusDays(1))).isFalse();
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(1))).isFalse();
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T07:59"), EIGHT)).isEqualTo(seoul("2026-10-12T08:00"));
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T08:00"), EIGHT)).isNull();
    }

    @Test
    void weekly_시작일이_주중이면_그_전_같은_요일은_제외() {
        // 수요일(10-14)부터 월·수 → 10-12(월)은 제외, 10-14(수) 포함, 10-19(월) 포함
        ReminderRule r = new ReminderRule(true, RepeatType.WEEKLY, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                null, MON.plusDays(2), null);
        assertThat(r.matches(MON)).isFalse();
        assertThat(r.matches(MON.plusDays(2))).isTrue();
        assertThat(r.matches(MON.plusDays(7))).isTrue();
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T09:00"), EIGHT)).isEqualTo(seoul("2026-10-14T08:00"));
    }

    @Test
    void weekly_일요일_7과_모든_요일() {
        ReminderRule sun = new ReminderRule(true, RepeatType.WEEKLY, EnumSet.of(DayOfWeek.SUNDAY), null, MON, null);
        assertThat(sun.matches(MON.plusDays(6))).isTrue();  // 10-18 일
        assertThat(sun.matches(MON.plusDays(5))).isFalse(); // 10-17 토
        ReminderRule all = new ReminderRule(true, RepeatType.WEEKLY, EnumSet.allOf(DayOfWeek.class), null, MON, null);
        for (int i = 0; i < 7; i++) {
            assertThat(all.matches(MON.plusDays(i))).isTrue();
        }
    }

    @Test
    void 종료일_당일의_새벽_회차는_다음날_새벽에_발송되고_그_다음은_없다() {
        // 종료일 = 월요일, 02:00 → "월요일 약"은 화요일 02:00 에 발송. 화요일 기록 날짜는 종료 후.
        ReminderRule r = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON.minusDays(3), MON);
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T23:00"), TWO_AM)).isEqualTo(seoul("2026-10-13T02:00"));
        assertThat(r.nextFireAt(CALC, seoul("2026-10-13T03:59"), TWO_AM)).isNull();
    }

    @Test
    void 시작일_당일의_새벽_회차는_시작일_다음날_새벽() {
        // 월요일 시작, 02:00 → 월요일 01:00(기록 날짜 일요일)에는 일요일 회차가 없으므로 다음은 화요일 02:00
        ReminderRule r = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON, null);
        assertThat(r.matches(MON.minusDays(1))).isFalse();
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T01:00"), TWO_AM)).isEqualTo(seoul("2026-10-13T02:00"));
    }

    @Test
    void 요일_규칙은_03시59분_회차와_04시_회차를_서로_다른_기록날짜로_본다() {
        // 월요일만, 시각 03:59 와 04:00. 월요일 기록 날짜의 회차 = 월 04:00, 화 03:59
        ReminderRule r = new ReminderRule(true, RepeatType.WEEKLY, EnumSet.of(DayOfWeek.MONDAY), null,
                LocalDate.of(2026, 10, 1), null);
        List<LocalTime> times = List.of(LocalTime.of(3, 59), LocalTime.of(4, 0));
        // 월 03:00(기록 날짜 일) → 다음 = 월 04:00
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T03:00"), times)).isEqualTo(seoul("2026-10-12T04:00"));
        // 월 04:00 직후 → 다음 = 화 03:59(아직 월요일 회차)
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T04:00"), times)).isEqualTo(seoul("2026-10-13T03:59"));
        // 화 03:59 이후 → 다음 주 월 04:00 (화 04:00 은 화요일 회차라 제외)
        assertThat(r.nextFireAt(CALC, seoul("2026-10-13T03:59"), times)).isEqualTo(seoul("2026-10-19T04:00"));
    }

    @Test
    void nextFireAt_400일_한도_경계() {
        Instant now = seoul("2026-10-12T09:00");
        ReminderRule at400 = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON.plusDays(400), null);
        assertThat(at400.nextFireAt(CALC, now, EIGHT))
                .isEqualTo(CALC.slotInstant(MON.plusDays(400), LocalTime.of(8, 0)));
        ReminderRule at401 = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON.plusDays(401), null);
        assertThat(at401.nextFireAt(CALC, now, EIGHT)).isNull();
    }

    @Test
    void nextFireAt_투약_시각이_비어_있으면_null() {
        ReminderRule r = new ReminderRule(true, RepeatType.DAILY, Set.of(), null, MON, null);
        assertThat(r.nextFireAt(CALC, seoul("2026-10-12T09:00"), List.of())).isNull();
    }
}
