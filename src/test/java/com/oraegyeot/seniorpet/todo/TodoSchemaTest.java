package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V4 마이그레이션의 CHECK·유니크·복합 외래키: API 를 거치지 않은 잘못된 값도 DB 가 막는다. */
class TodoSchemaTest extends ApiTestSupport {

    private UUID userId;
    private UUID petId;

    @BeforeEach
    void setUp() throws Exception {
        String token = newDisposableUserToken();
        userId = userIdOf(token);
        petId = UUID.fromString(createPet(token));
    }

    private UUID insertTodo(String title, String memo, String hospitalNote) {
        return jdbc.sql("""
                        insert into todos (user_id, pet_id, title, due_date, memo, hospital_note)
                        values (:u, :p, :title, date '2026-10-20', :memo, :note)
                        returning id
                        """)
                .param("u", userId).param("p", petId).param("title", title).param("memo", memo).param("note", hospitalNote)
                .query(UUID.class).single();
    }

    private void insertDispatch(UUID todoId, String kind, String dueDate, String status) {
        jdbc.sql("""
                        insert into todo_dispatches (user_id, todo_id, kind, due_date, fire_at, status)
                        values (:u, :t, :kind, cast(:d as date), now(), :status)
                        """)
                .param("u", userId).param("t", todoId).param("kind", kind).param("d", dueDate).param("status", status)
                .update();
    }

    @Test
    void 제목은_공백_제외_1자_이상_40자_이하() {
        assertThatThrownBy(() -> insertTodo("", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTodo("   ", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTodo("가".repeat(41), null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertTodo("가".repeat(40), null, null)).doesNotThrowAnyException();
        assertThatCode(() -> insertTodo("😀".repeat(40), null, null)).doesNotThrowAnyException();
    }

    @Test
    void 메모_200자_병원_메모_500자_제한() {
        assertThatThrownBy(() -> insertTodo("a", "가".repeat(201), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTodo("a", null, "가".repeat(501))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertTodo("a", "가".repeat(200), "나".repeat(500))).doesNotThrowAnyException();
    }

    @Test
    void 남의_반려동물을_가리키는_할_일은_복합_외래키가_막는다() throws Exception {
        UUID other = userIdOf(newDisposableUserToken());
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into todos (user_id, pet_id, title, due_date) values (:u, :p, 'a', date '2026-10-20')
                        """).param("u", other).param("p", petId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 발송_기록의_kind_status_값과_중복_키() {
        UUID todo = insertTodo("a", null, null);
        assertThatThrownBy(() -> insertDispatch(todo, "week_before", "2026-10-20", "sent"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDispatch(todo, "day_before", "2026-10-20", "done"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertDispatch(todo, "day_before", "2026-10-20", "claimed")).doesNotThrowAnyException();
        // 같은 (할 일, 종류, 날짜) 중복은 거부
        assertThatThrownBy(() -> insertDispatch(todo, "day_before", "2026-10-20", "sent"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 종류나 날짜가 다르면 허용
        assertThatCode(() -> insertDispatch(todo, "due_day", "2026-10-20", "sent")).doesNotThrowAnyException();
        assertThatCode(() -> insertDispatch(todo, "day_before", "2026-10-21", "skipped_done")).doesNotThrowAnyException();
        assertThatCode(() -> insertDispatch(todo, "due_day", "2026-10-21", "no_device")).doesNotThrowAnyException();
        assertThatCode(() -> insertDispatch(todo, "due_day", "2026-10-22", "failed")).doesNotThrowAnyException();
    }

    @Test
    void 남의_할_일에_대한_발송_기록은_복합_외래키가_막는다() throws Exception {
        UUID todo = insertTodo("a", null, null);
        UUID other = userIdOf(newDisposableUserToken());
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into todo_dispatches (user_id, todo_id, kind, due_date, fire_at, status)
                        values (:u, :t, 'day_before', date '2026-10-20', now(), 'claimed')
                        """).param("u", other).param("t", todo).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 할_일을_지우면_발송_기록도_함께_지워진다() {
        UUID todo = insertTodo("a", null, null);
        insertDispatch(todo, "due_day", "2026-10-20", "sent");
        jdbc.sql("delete from todos where id = :id").param("id", todo).update();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("select count(*) from todo_dispatches where todo_id = :id")
                .param("id", todo).query(Long.class).single()).isZero();
    }
}
