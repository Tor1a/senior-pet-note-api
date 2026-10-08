package com.oraegyeot.seniorpet.recorddate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** app.zone 을 바꿨을 때 기록 날짜·발송 시각이 그 시간대 기준으로 계산되는지. */
class RecordDateCalculatorZoneTest {

    private static final List<String> ZONES = List.of("UTC", "Asia/Seoul", "America/New_York");
    private static final LocalDate D = LocalDate.of(2026, 10, 6);

    private static RecordDateCalculator calc(ZoneId zone, Instant now) {
        return new RecordDateCalculator(Clock.fixed(now, ZoneOffset.UTC), zone);
    }

    private static Instant local(ZoneId zone, int day, int h, int m) {
        return LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant();
    }

    @Test
    void 기록날짜는_zone별로_현지_04시_컷오프() {
        for (String id : ZONES) {
            ZoneId z = ZoneId.of(id);
            assertThat(calc(z, local(z, 6, 3, 59)).currentRecordDate()).as(id).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(calc(z, local(z, 6, 4, 0)).currentRecordDate()).as(id).isEqualTo(D);
            assertThat(calc(z, local(z, 6, 0, 0)).currentRecordDate()).as(id).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(calc(z, local(z, 6, 23, 59)).currentRecordDate()).as(id).isEqualTo(D);
        }
    }

    @Test
    void 같은_순간도_zone에_따라_기록날짜가_다르다() {
        Instant now = Instant.parse("2026-10-06T00:00:00Z"); // 서울 09:00, UTC 00:00, 뉴욕 전날 20:00
        assertThat(calc(ZoneId.of("Asia/Seoul"), now).currentRecordDate()).isEqualTo(D);
        assertThat(calc(ZoneId.of("UTC"), now).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(calc(ZoneId.of("America/New_York"), now).currentRecordDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void 발송시각은_zone_현지_시각이고_04시_전은_다음날() {
        for (String id : ZONES) {
            ZoneId z = ZoneId.of(id);
            var c = calc(z, Instant.EPOCH);
            assertThat(c.slotInstant(D, LocalTime.of(8, 0))).as(id).isEqualTo(local(z, 6, 8, 0));
            assertThat(c.slotInstant(D, LocalTime.of(4, 0))).as(id).isEqualTo(local(z, 6, 4, 0));
            assertThat(c.slotInstant(D, LocalTime.of(2, 0))).as(id).isEqualTo(local(z, 7, 2, 0));
            assertThat(c.recordDateOf(c.slotInstant(D, LocalTime.of(3, 59)))).as(id).isEqualTo(D);
        }
    }

    @Test
    void 분단위_회차의_시각은_zone_현지_시각() {
        for (String id : ZONES) {
            ZoneId z = ZoneId.of(id);
            var c = calc(z, Instant.EPOCH);
            var slots = c.minuteSlotsBetween(local(z, 7, 3, 58), local(z, 7, 4, 1));
            assertThat(slots).extracting(RecordDateCalculator.Slot::time)
                    .as(id).containsExactly(LocalTime.of(3, 59), LocalTime.of(4, 0), LocalTime.of(4, 1));
            assertThat(slots).extracting(RecordDateCalculator.Slot::recordDate)
                    .as(id).containsExactly(D, LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7));
        }
    }
}
