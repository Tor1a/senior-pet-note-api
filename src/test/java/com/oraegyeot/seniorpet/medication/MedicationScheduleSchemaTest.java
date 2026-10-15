package com.oraegyeot.seniorpet.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V7 제약과 삭제 연쇄: API 를 거치지 않은 잘못된 값도 DB 가 막고, 탈퇴·반려동물 삭제 때 이력도 사라진다. */
class MedicationScheduleSchemaTest extends ApiTestSupport {

    private UUID userId;
    private UUID petId;
    private UUID medId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = newDisposableUserToken();
        userId = userIdOf(token);
        petId = UUID.fromString(createPet(token));
        medId = UUID.fromString(createMedication(token, petId.toString(), List.of("08:00")));
        // 등록 시 만들어진 열린 버전을 지우고 직접 넣어 본다
        jdbc.sql("delete from medication_schedule_versions where medication_id = :m").param("m", medId).update();
    }

    private void insert(UUID user, UUID med, String times, String from, String to) {
        jdbc.sql("insert into medication_schedule_versions (user_id, medication_id, times, valid_from, valid_to) "
                        + "values (:u, :m, cast(:t as time[]), cast(:f as date), cast(:to as date))")
                .param("u", user).param("m", med).param("t", times).param("f", from).param("to", to).update();
    }

    private long count(UUID med) {
        return jdbc.sql("select count(*) from medication_schedule_versions where medication_id = :m")
                .param("m", med).query(Long.class).single();
    }

    @Test
    void 열린_버전은_약당_하나만() {
        insert(userId, medId, "{08:00}", "2026-10-01", null);
        assertThatThrownBy(() -> insert(userId, medId, "{09:00}", "2026-10-05", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(userId, medId, "{07:00}", "2026-09-01", "2026-09-30")).doesNotThrowAnyException();
    }

    @Test
    void 같은_시작일_중복과_뒤집힌_기간은_거부() {
        insert(userId, medId, "{08:00}", "2026-10-01", "2026-10-02");
        assertThatThrownBy(() -> insert(userId, medId, "{09:00}", "2026-10-01", "2026-10-03"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(userId, medId, "{09:00}", "2026-10-10", "2026-10-09"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 시각은_1개_이상_3개_이하이고_null_없음() {
        assertThatThrownBy(() -> insert(userId, medId, "{}", "2026-10-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(userId, medId, "{06:00,07:00,08:00,09:00}", "2026-10-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(userId, medId, "{08:00,NULL}", "2026-10-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(userId, medId, "{06:00,07:00,08:00}", "2026-10-01", null)).doesNotThrowAnyException();
    }

    @Test
    void 남의_약을_가리키는_이력은_복합_외래키가_막는다() throws Exception {
        UUID other = userIdOf(newDisposableUserToken());
        assertThatThrownBy(() -> insert(other, medId, "{08:00}", "2026-10-01", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 탈퇴하면_이력도_사라진다() throws Exception {
        insert(userId, medId, "{08:00}", "2026-10-01", null);
        assertThat(count(medId)).isEqualTo(1);
        registerForCleanup(userId);
        call(jsonPost("/api/me/withdraw", java.util.Map.of("password", PASSWORD, "confirm", true)), token, 204);
        assertThat(count(medId)).isZero();
        assertThat(jdbc.sql("select count(*) from medication_schedule_versions where user_id = :u")
                .param("u", userId).query(Long.class).single()).isZero();
    }

    @Test
    void 반려동물을_지우면_이력도_사라진다() {
        insert(userId, medId, "{08:00}", "2026-10-01", null);
        jdbc.sql("delete from pets where id = :p and user_id = :u").param("p", petId).param("u", userId).update();
        assertThat(count(medId)).isZero();
    }
}
