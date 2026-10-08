package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 회원 탈퇴와 발송의 경합: 후보 조회 뒤 사용자가 삭제되어도 claim 은 오류 없이 건너뛴다. */
class ReminderClaimDeletedUserTest extends ApiTestSupport {

    @Autowired
    private ReminderDispatchQueries queries;

    @Test
    void 삭제된_사용자의_회차는_예외_없이_빈_값() throws Exception {
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        String pet = createPet(token);
        String med = createMedication(token, pet, List.of("08:00"));
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));

        // 탈퇴가 커밋된 직후 같은 후보로 claim 을 시도하는 상황
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), token, 204);

        Optional<Long> claimed = queries.claim(userId, UUID.fromString(med), LocalDate.of(2026, 10, 12),
                LocalTime.of(8, 0), Instant.parse("2026-10-11T23:00:00Z"));
        assertThat(claimed).isEmpty();
        Long rows = jdbc.sql("select count(*) from reminder_dispatches where medication_id = :m")
                .param("m", UUID.fromString(med)).query(Long.class).single();
        assertThat(rows).isZero();
    }

    @Test
    void 정상_회차는_여전히_선점되고_같은_회차는_두_번째가_빈_값() throws Exception {
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        String pet = createPet(token);
        String med = createMedication(token, pet, List.of("08:00"));
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
        Optional<Long> first = queries.claim(userId, UUID.fromString(med), LocalDate.of(2026, 10, 12),
                LocalTime.of(8, 0), Instant.parse("2026-10-11T23:00:00Z"));
        Optional<Long> second = queries.claim(userId, UUID.fromString(med), LocalDate.of(2026, 10, 12),
                LocalTime.of(8, 0), Instant.parse("2026-10-11T23:00:00Z"));
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void 외래키가_아닌_무결성_오류는_삼키지_않고_다시_던진다() throws Exception {
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        String pet = createPet(token);
        String med = createMedication(token, pet, List.of("08:00"));
        // scheduled_time 이 null → not null 위반(23502). FK 위반이 아니므로 예외가 그대로 나가야 한다
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> queries.claim(userId, UUID.fromString(med), LocalDate.of(2026, 10, 12), null,
                        Instant.parse("2026-10-11T23:00:00Z")));
    }
}
