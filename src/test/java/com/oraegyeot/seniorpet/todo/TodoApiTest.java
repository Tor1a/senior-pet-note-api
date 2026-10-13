package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 내 할 일 API (docs/api-todos.md). 시계는 2026-10-12(월) 12:00 서울로 고정한다(기록 날짜 2026-10-12).
 * 발송 작업은 부르지 않으므로(스케줄러 꺼짐) 날짜가 과거여도 무관하다.
 */
class TodoApiTest extends ApiTestSupport {

    private String token;
    private String pet;

    private void setUp() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        token = newDisposableUserToken();
        pet = createPet(token);
    }

    private Map<String, Object> body(String title, String dueDate) {
        Map<String, Object> b = new HashMap<>();
        b.put("title", title);
        b.put("dueDate", dueDate);
        return b;
    }

    private JsonNode post(Map<String, Object> body, int expected) throws Exception {
        return call(jsonPost("/api/pets/" + pet + "/todos", body), token, expected);
    }

    private JsonNode list(String query) throws Exception {
        return call(get("/api/pets/" + pet + "/todos" + query), token, 200);
    }

    private static void assertValidation(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 등록하면_201이고_필드가_그대로_돌아온다() throws Exception {
        setUp();
        Map<String, Object> b = body("  종합 접종  ", "2026-10-20");
        b.put("dueTime", "14:30");
        b.put("memo", "수첩 챙기기");
        b.put("hospitalNote", "다음 방문 때 상담");
        b.put("remind", false);
        JsonNode t = post(b, 201);

        assertThat(UUID.fromString(t.get("id").asText())).isNotNull();
        assertThat(t.get("petId").asText()).isEqualTo(pet);
        assertThat(t.get("title").asText()).isEqualTo("종합 접종"); // 앞뒤 공백 제거
        assertThat(t.get("dueDate").asText()).isEqualTo("2026-10-20");
        assertThat(t.get("dueTime").asText()).isEqualTo("14:30");
        assertThat(t.get("memo").asText()).isEqualTo("수첩 챙기기");
        assertThat(t.get("hospitalNote").asText()).isEqualTo("다음 방문 때 상담");
        assertThat(t.get("remind").asBoolean()).isFalse();
        assertThat(t.get("completed").asBoolean()).isFalse();
        assertThat(t.get("completedAt").isNull()).isTrue();
        assertThat(t.get("dateStatus").asText()).isEqualTo("future");
        assertThat(Instant.parse(t.get("createdAt").asText())).isNotNull();
        assertThat(Instant.parse(t.get("updatedAt").asText())).isNotNull();
        assertThat(t.size()).isEqualTo(13);
    }

    @Test
    void 선택_필드를_생략하면_null이고_remind는_true() throws Exception {
        setUp();
        Map<String, Object> b = body("구충", "2026-10-13");
        b.put("memo", "   ");
        b.put("hospitalNote", "");
        JsonNode t = post(b, 201);
        assertThat(t.get("dueTime").isNull()).isTrue();
        assertThat(t.get("memo").isNull()).isTrue();          // 빈 문자열은 null 로 저장
        assertThat(t.get("hospitalNote").isNull()).isTrue();
        assertThat(t.get("remind").asBoolean()).isTrue();
        assertThat(jdbc.sql("select memo is null and hospital_note is null from todos where id = :id")
                .param("id", UUID.fromString(t.get("id").asText())).query(Boolean.class).single()).isTrue();
    }

    @Test
    void 수정은_전체_교체이고_완료_상태는_유지된다() throws Exception {
        setUp();
        Map<String, Object> b = body("검진", "2026-10-20");
        b.put("dueTime", "10:00");
        b.put("memo", "공복");
        String id = post(b, 201).get("id").asText();
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);

        Map<String, Object> next = body("정기 검진", "2026-10-25"); // dueTime·memo 생략 = 지워짐
        next.put("hospitalNote", "혈액 검사 함");
        next.put("remind", false);
        JsonNode t = call(jsonPut("/api/todos/" + id, next), token, 200);

        assertThat(t.get("id").asText()).isEqualTo(id);
        assertThat(t.get("title").asText()).isEqualTo("정기 검진");
        assertThat(t.get("dueDate").asText()).isEqualTo("2026-10-25");
        assertThat(t.get("dueTime").isNull()).isTrue();
        assertThat(t.get("memo").isNull()).isTrue();
        assertThat(t.get("hospitalNote").asText()).isEqualTo("혈액 검사 함");
        assertThat(t.get("remind").asBoolean()).isFalse();
        assertThat(t.get("completed").asBoolean()).isTrue(); // 완료 상태는 completion API 로만 바뀐다
    }

    @Test
    void 완료와_되돌리기는_멱등이고_최초_completedAt을_유지한다() throws Exception {
        setUp();
        String id = post(body("접종", "2026-10-20"), 201).get("id").asText();
        JsonNode first = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        assertThat(first.get("completed").asBoolean()).isTrue();
        String completedAt = first.get("completedAt").asText();
        assertThat(Instant.parse(completedAt)).isEqualTo(Instant.parse("2026-10-12T03:00:00Z"));

        setSeoulTime(2026, 10, 12, 13, 0);
        JsonNode again = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        assertThat(again.get("completedAt").asText()).isEqualTo(completedAt); // 다시 true 를 보내도 최초값

        JsonNode undone = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", false)), token, 200);
        assertThat(undone.get("completed").asBoolean()).isFalse();
        assertThat(undone.get("completedAt").isNull()).isTrue();
        JsonNode undoneAgain = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", false)), token, 200);
        assertThat(undoneAgain.get("completed").asBoolean()).isFalse();

        // 완료 → 목록 이동
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        assertThat(list("").get("todos")).isEmpty();
        assertThat(list("?status=done").get("todos")).hasSize(1);
    }

    @Test
    void completed_가_없으면_400() throws Exception {
        setUp();
        String id = post(body("접종", "2026-10-20"), 201).get("id").asText();
        assertValidation(call(jsonPut("/api/todos/" + id + "/completion", Map.of()), token, 400));
    }

    @Test
    void 삭제하면_204이고_다시_삭제하면_404() throws Exception {
        setUp();
        String id = post(body("접종", "2026-10-20"), 201).get("id").asText();
        call(delete("/api/todos/" + id), token, 204);
        assertThat(call(delete("/api/todos/" + id), token, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(list("").get("todos")).isEmpty();
        assertThat(jdbc.sql("select count(*) from todos where id = :id").param("id", UUID.fromString(id))
                .query(Long.class).single()).isZero();
    }

    @Test
    void 미완료_정렬은_날짜_시각_시각없음_뒤_등록순() throws Exception {
        setUp();
        createTodo(token, pet, "D", "2026-10-21", null);
        createTodo(token, pet, "B", "2026-10-20", "15:00");
        createTodo(token, pet, "A", "2026-10-20", "09:00");
        createTodo(token, pet, "C1", "2026-10-20", null);
        setSeoulTime(2026, 10, 12, 12, 1);
        createTodo(token, pet, "C2", "2026-10-20", null);
        createTodo(token, pet, "P", "2026-10-01", "23:00"); // 지난 일정도 앞에 온다

        JsonNode todos = list("").get("todos");
        assertThat(titles(todos)).containsExactly("P", "A", "B", "C1", "C2", "D");
        assertThat(list("?status=open").get("todos")).hasSize(6); // status=open 은 생략과 같다
    }

    private static List<String> titles(JsonNode todos) {
        return java.util.stream.StreamSupport.stream(todos.spliterator(), false)
                .map(n -> n.get("title").asText()).toList();
    }

    @Test
    void 완료_목록은_최근_완료_순이고_최대_100개() throws Exception {
        setUp();
        UUID userId = userIdOf(token);
        jdbc.sql("""
                        insert into todos (user_id, pet_id, title, due_date, completed_at)
                        select :u, :p, 'done-' || g, date '2026-10-01', timestamptz '2026-10-01 00:00:00+00' + g * interval '1 minute'
                          from generate_series(1, 101) g
                        """)
                .param("u", userId).param("p", UUID.fromString(pet)).update();
        JsonNode todos = list("?status=done").get("todos");
        assertThat(todos).hasSize(100);
        assertThat(todos.get(0).get("title").asText()).isEqualTo("done-101"); // 가장 최근 완료
        assertThat(todos.get(99).get("title").asText()).isEqualTo("done-2");
        assertThat(todos.get(0).get("completed").asBoolean()).isTrue();
        assertThat(list("").get("todos")).isEmpty();
    }

    @Test
    void dateStatus_4종과_recordDate는_기록_날짜_기준() throws Exception {
        setUp();
        createTodo(token, pet, "지난", "2026-10-11", null);
        createTodo(token, pet, "오늘", "2026-10-12", null);
        createTodo(token, pet, "내일", "2026-10-13", null);
        createTodo(token, pet, "그후", "2026-10-14", null);
        JsonNode res = list("");
        assertThat(res.get("recordDate").asText()).isEqualTo("2026-10-12");
        assertThat(statuses(res.get("todos"))).containsExactly("past", "today", "tomorrow", "future");
    }

    @Test
    void 새벽_4시_전에는_기록_날짜가_전날이라_dateStatus도_전날_기준() throws Exception {
        setUp();
        createTodo(token, pet, "오늘", "2026-10-12", null);
        createTodo(token, pet, "내일", "2026-10-13", null);
        setSeoulTime(2026, 10, 13, 3, 59); // 기록 날짜 10-12
        JsonNode res = list("");
        assertThat(res.get("recordDate").asText()).isEqualTo("2026-10-12");
        assertThat(statuses(res.get("todos"))).containsExactly("today", "tomorrow");
        setSeoulTime(2026, 10, 13, 4, 0); // 기록 날짜 10-13
        res = list("");
        assertThat(res.get("recordDate").asText()).isEqualTo("2026-10-13");
        assertThat(statuses(res.get("todos"))).containsExactly("past", "today");
    }

    @Test
    void 완료_항목에도_dateStatus가_내려간다() throws Exception {
        setUp();
        String id = createTodo(token, pet, "내일", "2026-10-13", null).get("id").asText();
        JsonNode t = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        assertThat(t.get("dateStatus").asText()).isEqualTo("tomorrow");
        assertThat(statuses(list("?status=done").get("todos"))).containsExactly("tomorrow");
    }

    private static List<String> statuses(JsonNode todos) {
        return java.util.stream.StreamSupport.stream(todos.spliterator(), false)
                .map(n -> n.get("dateStatus").asText()).toList();
    }

    @Test
    void 제목_검증() throws Exception {
        setUp();
        assertValidation(post(body("   ", "2026-10-20"), 400));
        assertValidation(post(body("", "2026-10-20"), 400));
        assertValidation(post(body("가".repeat(41), "2026-10-20"), 400));
        post(body("가".repeat(40), "2026-10-20"), 201);
        post(body("😀".repeat(40), "2026-10-20"), 201); // 코드 포인트 기준 40자
        assertValidation(post(body("😀".repeat(41), "2026-10-20"), 400));
        post(body(" " + "나".repeat(40) + " ", "2026-10-20"), 201); // 공백 제거 후 40자
        Map<String, Object> noTitle = new HashMap<>();
        noTitle.put("dueDate", "2026-10-20");
        assertValidation(post(noTitle, 400));
    }

    @Test
    void 날짜_검증은_기록_날짜_기준_400일_전부터_730일_후까지() throws Exception {
        setUp();
        post(body("a", "2025-09-07"), 201);  // 2026-10-12 - 400일
        assertValidation(post(body("a", "2025-09-06"), 400));
        post(body("a", "2028-10-11"), 201);  // 2026-10-12 + 730일
        assertValidation(post(body("a", "2028-10-12"), 400));
        Map<String, Object> noDate = new HashMap<>();
        noDate.put("title", "a");
        assertValidation(post(noDate, 400));
        assertValidation(post(body("a", "2026-13-01"), 400));
        assertValidation(post(body("a", "내일"), 400));
    }

    @Test
    void 시각_검증() throws Exception {
        setUp();
        for (String bad : List.of("24:00", "9:30", "14:60", "14:30:00", "", "abc")) {
            Map<String, Object> b = body("a", "2026-10-20");
            b.put("dueTime", bad);
            assertValidation(post(b, 400));
        }
        for (String ok : List.of("00:00", "23:59")) {
            Map<String, Object> b = body("a", "2026-10-20");
            b.put("dueTime", ok);
            assertThat(post(b, 201).get("dueTime").asText()).isEqualTo(ok);
        }
        Map<String, Object> explicitNull = body("a", "2026-10-20");
        explicitNull.put("dueTime", null);
        assertThat(post(explicitNull, 201).get("dueTime").isNull()).isTrue();
    }

    @Test
    void 메모_길이_검증() throws Exception {
        setUp();
        Map<String, Object> b = body("a", "2026-10-20");
        b.put("memo", "가".repeat(200));
        b.put("hospitalNote", "나".repeat(500));
        post(b, 201);
        b.put("memo", "가".repeat(201));
        assertValidation(post(b, 400));
        b.put("memo", "😀".repeat(200));
        post(b, 201); // 코드 포인트 기준
        b.put("hospitalNote", "나".repeat(501));
        assertValidation(post(b, 400));
        b.put("memo", null);
        b.put("hospitalNote", "😀".repeat(501));
        assertValidation(post(b, 400));
    }

    @Test
    void 수정도_같은_검증을_거친다() throws Exception {
        setUp();
        String id = post(body("a", "2026-10-20"), 201).get("id").asText();
        assertValidation(call(jsonPut("/api/todos/" + id, body(" ", "2026-10-20")), token, 400));
        assertValidation(call(jsonPut("/api/todos/" + id, body("a", "2030-01-01")), token, 400));
        assertThat(call(get("/api/pets/" + pet + "/todos"), token, 200).get("todos").get(0).get("title").asText())
                .isEqualTo("a");
    }

    @Test
    void status_값이_틀리면_400() throws Exception {
        setUp();
        assertValidation(call(get("/api/pets/" + pet + "/todos?status=all"), token, 400));
        assertValidation(call(get("/api/pets/" + pet + "/todos?status="), token, 400));
        assertValidation(call(get("/api/pets/" + pet + "/todos?status=DONE"), token, 400));
    }

    @Test
    void 사용자당_300개까지이고_301번째는_409_TODO_LIMIT_REACHED() throws Exception {
        setUp();
        UUID userId = userIdOf(token);
        jdbc.sql("""
                        insert into todos (user_id, pet_id, title, due_date)
                        select :u, :p, 't' || g, date '2026-10-20' from generate_series(1, 299) g
                        """)
                .param("u", userId).param("p", UUID.fromString(pet)).update();
        post(body("300번째", "2026-10-20"), 201);
        JsonNode err = post(body("301번째", "2026-10-20"), 409);
        assertThat(err.get("code").asText()).isEqualTo("TODO_LIMIT_REACHED");
        assertThat(err.get("message").asText()).isNotBlank();
        // 완료한 항목도 한도에 포함된다. 삭제하면 다시 등록할 수 있다
        String id = list("").get("todos").get(0).get("id").asText();
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        post(body("또 시도", "2026-10-20"), 409);
        call(delete("/api/todos/" + id), token, 204);
        post(body("삭제 뒤 등록", "2026-10-20"), 201);
    }

    @Test
    void 한도는_사용자별이다() throws Exception {
        setUp();
        UUID userId = userIdOf(token);
        jdbc.sql("""
                        insert into todos (user_id, pet_id, title, due_date)
                        select :u, :p, 't' || g, date '2026-10-20' from generate_series(1, 300) g
                        """)
                .param("u", userId).param("p", UUID.fromString(pet)).update();
        String other = newDisposableUserToken();
        createTodo(other, createPet(other), "다른 사용자", "2026-10-20", null);
    }

    @Test
    void updatedAt은_DB_값과_같다() throws Exception {
        setUp();
        JsonNode created = post(body("a", "2026-10-20"), 201);
        String id = created.get("id").asText();
        JsonNode updated = call(jsonPut("/api/todos/" + id, body("b", "2026-10-21")), token, 200);
        JsonNode done = call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), token, 200);
        Instant db = jdbc.sql("select updated_at from todos where id = :id").param("id", UUID.fromString(id))
                .query(OffsetDateTime.class).single().toInstant();
        assertThat(Instant.parse(done.get("updatedAt").asText())).isEqualTo(db);
        // 수정 응답 시점의 값도 그 시점 DB 값이어야 한다(시계는 2026-10-12 로 고정이라 앱 값이면 12:00 UTC 03:00 이 된다)
        assertThat(Instant.parse(updated.get("updatedAt").asText())).isNotEqualTo(Instant.parse("2026-10-12T03:00:00Z"));
        assertThat(Instant.parse(updated.get("updatedAt").asText())).isBeforeOrEqualTo(db);
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(get("/api/pets/" + UUID.randomUUID() + "/todos")).andExpect(status().isUnauthorized());
        mvc.perform(jsonPost("/api/pets/" + UUID.randomUUID() + "/todos", Map.of("title", "a", "dueDate", "2026-10-20")))
                .andExpect(status().isUnauthorized());
        mvc.perform(jsonPut("/api/todos/" + UUID.randomUUID(), Map.of("title", "a", "dueDate", "2026-10-20")))
                .andExpect(status().isUnauthorized());
        mvc.perform(jsonPut("/api/todos/" + UUID.randomUUID() + "/completion", Map.of("completed", true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/todos/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void id_형식이_UUID가_아니면_404() throws Exception {
        setUp();
        call(delete("/api/todos/not-a-uuid"), token, 404);
    }
}
