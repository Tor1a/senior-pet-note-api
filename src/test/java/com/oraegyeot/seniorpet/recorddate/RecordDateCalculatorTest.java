package com.oraegyeot.seniorpet.recorddate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 기록 날짜(새벽 4시 규칙) 단위 테스트. */
class RecordDateCalculatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final RecordDateCalculator CALC = new RecordDateCalculator(Clock.systemUTC(), SEOUL);

    private static RecordDateCalculator at(int h, int m) {
        var instant = LocalDateTime.of(2026, 10, 6, h, m).atZone(SEOUL).toInstant();
        return new RecordDateCalculator(Clock.fixed(instant, ZoneOffset.UTC), SEOUL);
    }

    @Test
    void 서울_03시59분은_전날() {
        assertThat(at(3, 59).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void 서울_04시00분은_당일() {
        assertThat(at(4, 0).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void 자정과_밤11시59분() {
        assertThat(at(0, 0).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(at(23, 59).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void 서버_시간대가_아니라_서울_기준() {
        // UTC 2026-10-05 19:00 = 서울 10-06 04:00 → 10-06
        var calc = new RecordDateCalculator(Clock.fixed(
                LocalDateTime.of(2026, 10, 5, 19, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC), SEOUL);
        assertThat(calc.currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    // ---- 투약 알림 발송 시각(slotInstant) ----

    private static final LocalDate D = LocalDate.of(2026, 10, 6);

    private static Instant seoul(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + "+09:00").toInstant();
    }

    @Test
    void 발송시각_04시_이후_시각은_기록날짜_당일() {
        assertThat(CALC.slotInstant(D, LocalTime.of(8, 0))).isEqualTo(seoul("2026-10-06T08:00"));
        assertThat(CALC.slotInstant(D, LocalTime.of(4, 0))).isEqualTo(seoul("2026-10-06T04:00"));
        assertThat(CALC.slotInstant(D, LocalTime.of(23, 59))).isEqualTo(seoul("2026-10-06T23:59"));
    }

    @Test
    void 발송시각_04시_전_시각은_기록날짜_다음날() {
        assertThat(CALC.slotInstant(D, LocalTime.of(2, 0))).isEqualTo(seoul("2026-10-07T02:00"));
        assertThat(CALC.slotInstant(D, LocalTime.of(3, 59))).isEqualTo(seoul("2026-10-07T03:59"));
        assertThat(CALC.slotInstant(D, LocalTime.of(0, 0))).isEqualTo(seoul("2026-10-07T00:00"));
    }

    @Test
    void 발송시각의_기록날짜는_원래_기록날짜() {
        for (LocalTime t : List.of(LocalTime.of(0, 0), LocalTime.of(3, 59), LocalTime.of(4, 0), LocalTime.of(23, 59))) {
            assertThat(CALC.recordDateOf(CALC.slotInstant(D, t))).isEqualTo(D);
        }
    }

    @Test
    void 분단위_회차는_시작_제외_끝_포함이고_04시_경계를_넘는다() {
        var slots = CALC.minuteSlotsBetween(seoul("2026-10-07T03:55"), seoul("2026-10-07T04:05"));
        assertThat(slots).hasSize(10);
        assertThat(slots.get(0).time()).isEqualTo(LocalTime.of(3, 56));
        assertThat(slots.get(0).recordDate()).isEqualTo(LocalDate.of(2026, 10, 6)); // 03:56 = 전날 회차
        assertThat(slots.get(4).time()).isEqualTo(LocalTime.of(4, 0));
        assertThat(slots.get(4).recordDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(slots.get(9).fireAt()).isEqualTo(seoul("2026-10-07T04:05"));
        slots.forEach(s -> assertThat(CALC.slotInstant(s.recordDate(), s.time())).isEqualTo(s.fireAt()));
    }

    @Test
    void 분단위_회차는_자정을_넘고_초는_버린다() {
        var slots = CALC.minuteSlotsBetween(
                seoul("2026-10-06T23:58:30"), seoul("2026-10-07T00:01:59"));
        assertThat(slots).extracting(RecordDateCalculator.Slot::time)
                .containsExactly(LocalTime.of(23, 59), LocalTime.of(0, 0), LocalTime.of(0, 1));
        assertThat(slots).extracting(RecordDateCalculator.Slot::recordDate).containsOnly(D);
    }
}
