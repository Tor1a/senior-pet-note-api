package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 알림 반복 규칙 판정·다음 발송 시각 단위 테스트. 2026-10-12 = 월요일. */
class ReminderRuleTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final List<LocalTime> MORNING_EVENING = List.of(LocalTime.of(8, 0), LocalTime.of(20, 0));

    private static Instant seoul(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+09:00").toInstant();
    }

    private static ReminderRule daily(LocalDate start, LocalDate end) {
        return new ReminderRule(true, RepeatType.DAILY, Set.of(), null, start, end);
    }

    private static ReminderRule weekly(DayOfWeek... days) {
        return new ReminderRule(true, RepeatType.WEEKLY, EnumSet.of(days[0], days), null, LocalDate.of(2026, 10, 1), null);
    }

    @Test
    void 날짜_확인() {
        assertThat(MON.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void daily는_시작일부터_매일() {
        ReminderRule r = daily(MON, null);
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(1))).isTrue();
        assertThat(r.matches(MON.plusDays(365))).isTrue();
    }

    @Test
    void weekly는_지정한_요일만() {
        ReminderRule r = weekly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(1))).isFalse(); // 화
        assertThat(r.matches(MON.plusDays(2))).isTrue();  // 수
        assertThat(r.matches(MON.plusDays(3))).isFalse(); // 목
        assertThat(r.matches(MON.plusDays(7))).isTrue();  // 다음 주 월
    }

    @Test
    void interval은_시작일_기준_N일마다() {
        LocalDate start = LocalDate.of(2026, 10, 5);
        ReminderRule r = new ReminderRule(true, RepeatType.INTERVAL, Set.of(), 3, start, null);
        assertThat(r.matches(start)).isTrue();
        assertThat(r.matches(start.plusDays(1))).isFalse();
        assertThat(r.matches(start.plusDays(2))).isFalse();
        assertThat(r.matches(start.plusDays(3))).isTrue();
        assertThat(r.matches(start.plusDays(6))).isTrue();
        assertThat(r.matches(start.minusDays(3))).isFalse(); // 시작일 이전은 간격이 맞아도 제외
    }

    @Test
    void 시작일_이전은_제외() {
        assertThat(daily(MON, null).matches(MON.minusDays(1))).isFalse();
    }

    @Test
    void 종료일_당일은_포함_다음날은_제외() {
        ReminderRule r = daily(MON.minusDays(7), MON);
        assertThat(r.matches(MON)).isTrue();
        assertThat(r.matches(MON.plusDays(1))).isFalse();
    }

    @Test
    void 꺼진_규칙은_어떤_날도_해당없고_nextFireAt은_null() {
        ReminderRule r = new ReminderRule(false, RepeatType.DAILY, Set.of(), null, MON, null);
        assertThat(r.matches(MON)).isFalse();
        assertThat(r.nextFireAt(seoul("2026-10-12T07:00"), MORNING_EVENING)).isNull();
    }

    @Test
    void 종료일이_지나면_nextFireAt은_null() {
        ReminderRule r = daily(MON.minusDays(7), MON.minusDays(1));
        assertThat(r.nextFireAt(seoul("2026-10-12T07:00"), MORNING_EVENING)).isNull();
        // 종료일 당일의 마지막 회차가 지난 뒤도 null
        assertThat(daily(MON.minusDays(7), MON).nextFireAt(seoul("2026-10-12T20:00"), MORNING_EVENING)).isNull();
    }

    @Test
    void nextFireAt_daily는_오늘_남은_회차_없으면_내일_첫_회차() {
        ReminderRule r = daily(MON, null);
        assertThat(r.nextFireAt(seoul("2026-10-12T09:00"), MORNING_EVENING)).isEqualTo(seoul("2026-10-12T20:00"));
        assertThat(r.nextFireAt(seoul("2026-10-12T21:00"), MORNING_EVENING)).isEqualTo(seoul("2026-10-13T08:00"));
    }

    @Test
    void nextFireAt은_지금보다_뒤인_회차만() {
        ReminderRule r = daily(MON, null);
        assertThat(r.nextFireAt(seoul("2026-10-12T08:00"), MORNING_EVENING)).isEqualTo(seoul("2026-10-12T20:00"));
    }

    @Test
    void nextFireAt_weekly는_다음_해당_요일() {
        ReminderRule r = weekly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(r.nextFireAt(seoul("2026-10-13T09:00"), MORNING_EVENING)).isEqualTo(seoul("2026-10-14T08:00"));
    }

    @Test
    void nextFireAt_시작일이_미래면_시작일의_첫_회차() {
        ReminderRule r = daily(MON.plusDays(10), null);
        assertThat(r.nextFireAt(seoul("2026-10-12T09:00"), MORNING_EVENING)).isEqualTo(seoul("2026-10-22T08:00"));
    }

    @Test
    void 새벽_02시_회차는_기록날짜의_요일로_판정하고_다음날_02시에_발송() {
        ReminderRule r = weekly(DayOfWeek.MONDAY);
        List<LocalTime> twoAm = List.of(LocalTime.of(2, 0));
        // 월요일 10:00 기준 다음 발송 = "월요일 약" → 화요일 02:00
        assertThat(r.nextFireAt(seoul("2026-10-12T10:00"), twoAm)).isEqualTo(seoul("2026-10-13T02:00"));
        // 월요일 01:00 은 아직 일요일 기록 날짜 → 일요일 회차(월 02:00)는 해당 없음, 다음은 화요일 02:00
        assertThat(r.nextFireAt(seoul("2026-10-12T01:00"), twoAm)).isEqualTo(seoul("2026-10-13T02:00"));
        // 화요일 01:00(기록 날짜 월요일) → 1시간 뒤 화요일 02:00
        assertThat(r.nextFireAt(seoul("2026-10-13T01:00"), twoAm)).isEqualTo(seoul("2026-10-13T02:00"));
    }

    @Test
    void 새벽_회차와_낮_회차가_섞이면_실제_시각_순서로() {
        ReminderRule r = daily(MON, null);
        List<LocalTime> times = List.of(LocalTime.of(1, 0), LocalTime.of(22, 0)); // 기록 날짜 기준 22:00 → 다음날 01:00
        assertThat(r.nextFireAt(seoul("2026-10-12T21:00"), times)).isEqualTo(seoul("2026-10-12T22:00"));
        assertThat(r.nextFireAt(seoul("2026-10-12T23:00"), times)).isEqualTo(seoul("2026-10-13T01:00"));
    }
}
