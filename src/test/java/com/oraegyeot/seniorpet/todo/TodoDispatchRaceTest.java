package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 선점과 발송 사이의 경합: 선점 뒤에 완료·삭제·탈퇴가 일어나도 오류 없이 처리한다. */
class TodoDispatchRaceTest extends ApiTestSupport {

    @MockitoSpyBean
    private TodoDispatchQueries queries;

    @Autowired
    private TodoDispatcher dispatcher;

    private static final Instant FIRE_AT = Instant.parse("2030-06-11T11:00:00Z"); // 서울 20:00

    @Test
    void 선점_뒤에_완료되면_skipped_done이고_발송하지_않는다() throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "android");
        String id = createTodo(token, pet, "접종", "2030-06-12", null).get("id").asText();

        // claim 직후(= 후보 조회 뒤, 발송 전)에 보호자가 완료 체크한 상황을 흉내 낸다
        doAnswer(inv -> {
            Object claimed = inv.callRealMethod();
            jdbc.sql("update todos set completed_at = now() where id = :id").param("id", UUID.fromString(id)).update();
            return claimed;
        }).when(queries).claim(any(), any(), any(), any(), any());

        setSeoulTime(2030, 6, 11, 20, 0);
        dispatcher.dispatchDue();

        assertThat(pushSender.sentTo(fcm)).isEmpty();
        assertThat(jdbc.sql("select status from todo_dispatches where todo_id = :id")
                .param("id", UUID.fromString(id)).query(String.class).list()).containsExactly("skipped_done");
    }

    @Test
    void 선점_뒤에_알림을_끄면_발송하지_않는다() throws Exception {
        assertSkippedAfterClaim("update todos set remind = false where id = :id");
    }

    @Test
    void 선점_뒤에_예정_날짜가_바뀌면_발송하지_않는다() throws Exception {
        assertSkippedAfterClaim("update todos set due_date = date '2030-06-20' where id = :id");
    }

    private void assertSkippedAfterClaim(String updateSql) throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "android");
        String id = createTodo(token, pet, "접종", "2030-06-12", null).get("id").asText();

        doAnswer(inv -> {
            Object claimed = inv.callRealMethod();
            jdbc.sql(updateSql).param("id", UUID.fromString(id)).update();
            return claimed;
        }).when(queries).claim(any(), any(), any(), any(), any());

        setSeoulTime(2030, 6, 11, 20, 0);
        dispatcher.dispatchDue();

        assertThat(pushSender.sentTo(fcm)).isEmpty();
        assertThat(jdbc.sql("select status from todo_dispatches where todo_id = :id")
                .param("id", UUID.fromString(id)).query(String.class).list()).containsExactly("skipped_done");
    }

    @Test
    void 삭제된_할_일의_선점은_예외_없이_빈_값() throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        String pet = createPet(token);
        UUID todoId = UUID.fromString(createTodo(token, pet, "접종", "2030-06-12", null).get("id").asText());
        jdbc.sql("delete from todos where id = :id").param("id", todoId).update();

        Optional<Long> claimed = queries.claim(userId, todoId, TodoDispatch.DAY_BEFORE, LocalDate.of(2030, 6, 12), FIRE_AT);
        assertThat(claimed).isEmpty();
    }

    @Test
    void 탈퇴한_사용자의_선점은_예외_없이_빈_값() throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        String pet = createPet(token);
        UUID todoId = UUID.fromString(createTodo(token, pet, "접종", "2030-06-12", null).get("id").asText());
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), token, 204);

        assertThat(queries.claim(userId, todoId, TodoDispatch.DUE_DAY, LocalDate.of(2030, 6, 12), FIRE_AT)).isEmpty();
        assertThat(jdbc.sql("select count(*) from todo_dispatches where todo_id = :id").param("id", todoId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void 정상_선점은_되고_같은_키의_두_번째는_빈_값() throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        UUID todoId = UUID.fromString(createTodo(token, createPet(token), "접종", "2030-06-12", null).get("id").asText());
        assertThat(queries.claim(userId, todoId, TodoDispatch.DAY_BEFORE, LocalDate.of(2030, 6, 12), FIRE_AT)).isPresent();
        assertThat(queries.claim(userId, todoId, TodoDispatch.DAY_BEFORE, LocalDate.of(2030, 6, 12), FIRE_AT)).isEmpty();
        assertThat(queries.claim(userId, todoId, TodoDispatch.DUE_DAY, LocalDate.of(2030, 6, 12), FIRE_AT)).isPresent();
    }

    @Test
    void 외래키가_아닌_무결성_오류는_삼키지_않고_다시_던진다() throws Exception {
        setSeoulTime(2030, 6, 10, 12, 0);
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        UUID todoId = UUID.fromString(createTodo(token, createPet(token), "접종", "2030-06-12", null).get("id").asText());
        // kind 가 null → not null 위반(23502). FK 위반이 아니므로 예외가 그대로 나가야 한다
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> queries.claim(userId, todoId, null, LocalDate.of(2030, 6, 12), FIRE_AT));
    }
}
