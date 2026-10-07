package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V2 마이그레이션의 CHECK·유니크 제약: API 를 거치지 않은 잘못된 조합도 DB 가 막는다. */
class ReminderSchemaTest extends ApiTestSupport {

    private UUID userId;
    private UUID medId;

    @BeforeEach
    void setUp() throws Exception {
        String token = newDisposableUserToken();
        userId = userIdOf(token);
        medId = UUID.fromString(createMedication(token, createPet(token), List.of("08:00")));
    }

    private void insert(String repeat, String days, Integer interval, String start, String end) {
        jdbc.sql("""
                        insert into medication_reminders
                               (user_id, medication_id, repeat_type, days_of_week, interval_days, start_date, end_date)
                        values (:u, :m, :repeat, cast(:days as smallint[]), :interval,
                                cast(:start as date), cast(:end as date))
                        """)
                .param("u", userId).param("m", medId).param("repeat", repeat).param("days", days)
                .param("interval", interval).param("start", start).param("end", end)
                .update();
    }

    @Test
    void 잘못된_규칙_조합은_CHECK_위반() {
        assertThatThrownBy(() -> insert("weekly", "{}", null, "2026-10-06", null))       // weekly + 빈 요일
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("interval", "{}", null, "2026-10-06", null))     // interval + 간격 없음
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("daily", "{}", null, "2026-10-06", "2026-10-05")) // end < start
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("daily", "{1}", null, "2026-10-06", null))        // daily + 요일
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("weekly", "{8}", null, "2026-10-06", null))       // 요일 범위 밖
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("interval", "{}", 1, "2026-10-06", null))         // 간격 범위 밖
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("daily", "{}", 3, "2026-10-06", null))            // daily + 간격
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 올바른_조합은_들어가고_약_1개당_1행() {
        assertThatCode(() -> insert("weekly", "{1,3}", null, "2026-10-06", "2026-10-06")).doesNotThrowAnyException();
        assertThatThrownBy(() -> insert("daily", "{}", null, "2026-10-06", null))         // 같은 약 두 번째 규칙
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 발송_기록은_회차당_1행() {
        String sql = """
                insert into reminder_dispatches (user_id, medication_id, record_date, scheduled_time, fire_at, status)
                values (:u, :m, date '2026-10-06', time '08:00', timestamptz '2026-10-05T23:00:00Z', 'claimed')
                """;
        jdbc.sql(sql).param("u", userId).param("m", medId).update();
        assertThatThrownBy(() -> jdbc.sql(sql).param("u", userId).param("m", medId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
