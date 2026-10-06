package com.oraegyeot.seniorpet.recorddate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 기록 날짜(새벽 4시 규칙) 단위 테스트. */
class RecordDateCalculatorTest {

    private static RecordDateCalculator at(int h, int m) {
        var instant = LocalDateTime.of(2026, 10, 6, h, m).atZone(RecordDateCalculator.ZONE).toInstant();
        return new RecordDateCalculator(Clock.fixed(instant, ZoneOffset.UTC));
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
                LocalDateTime.of(2026, 10, 5, 19, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        assertThat(calc.currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }
}
