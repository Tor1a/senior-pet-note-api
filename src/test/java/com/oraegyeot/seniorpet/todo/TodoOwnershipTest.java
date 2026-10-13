package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 소유자 격리: 다른 사용자의 반려동물·할 일에 접근하면 404, 본문의 userId 는 무시(보안 규칙 1~7). */
class TodoOwnershipTest extends ApiTestSupport {

    private static void assertNotFound(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("NOT_FOUND");
    }

    private Map<String, Object> input(String title) {
        return Map.of("title", title, "dueDate", "2026-10-20");
    }

    @Test
    void 남의_반려동물로_등록하거나_목록을_보면_404() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        createPet(bob);

        assertNotFound(call(jsonPost("/api/pets/" + alicePet + "/todos", input("침입")), bob, 404));
        assertNotFound(call(get("/api/pets/" + alicePet + "/todos"), bob, 404));
        assertNotFound(call(get("/api/pets/" + alicePet + "/todos?status=done"), bob, 404));
        assertNotFound(call(get("/api/pets/" + UUID.randomUUID() + "/todos"), bob, 404));
        assertThat(call(get("/api/pets/" + alicePet + "/todos"), alice, 200).get("todos")).isEmpty();
    }

    @Test
    void 남의_할_일_수정_완료_삭제는_404이고_원본은_그대로() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        createPet(bob);
        String id = createTodo(alice, alicePet, "접종", "2026-10-20", null).get("id").asText();

        assertNotFound(call(jsonPut("/api/todos/" + id, input("바꿈")), bob, 404));
        assertNotFound(call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), bob, 404));
        assertNotFound(call(delete("/api/todos/" + id), bob, 404));

        JsonNode mine = call(get("/api/pets/" + alicePet + "/todos"), alice, 200).get("todos");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("title").asText()).isEqualTo("접종");
        assertThat(mine.get(0).get("completed").asBoolean()).isFalse();
    }

    @Test
    void 목록은_사용자별로_격리된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        String bobPet = createPet(bob);
        createTodo(alice, alicePet, "앨리스 것", "2026-10-20", null);
        createTodo(bob, bobPet, "밥 것", "2026-10-20", null);

        JsonNode a = call(get("/api/pets/" + alicePet + "/todos"), alice, 200).get("todos");
        JsonNode b = call(get("/api/pets/" + bobPet + "/todos"), bob, 200).get("todos");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("title").asText()).isEqualTo("앨리스 것");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).get("title").asText()).isEqualTo("밥 것");
    }

    @Test
    void 본문의_userId와_petId는_무시되고_로그인_사용자로_저장된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        UUID aliceId = userIdOf(alice);
        UUID bobId = userIdOf(bob);
        String alicePet = createPet(alice);
        String bobPet = createPet(bob);

        JsonNode t = call(jsonPost("/api/pets/" + alicePet + "/todos",
                Map.of("title", "접종", "dueDate", "2026-10-20", "userId", bobId.toString(), "petId", bobPet)),
                alice, 201);
        assertThat(t.get("petId").asText()).isEqualTo(alicePet);
        UUID owner = jdbc.sql("select user_id from todos where id = :id")
                .param("id", UUID.fromString(t.get("id").asText())).query(UUID.class).single();
        assertThat(owner).isEqualTo(aliceId);

        // 수정 때 userId 를 넣어도 소유자는 바뀌지 않는다
        call(jsonPut("/api/todos/" + t.get("id").asText(),
                Map.of("title", "접종2", "dueDate", "2026-10-21", "userId", bobId.toString())), alice, 200);
        UUID ownerAfter = jdbc.sql("select user_id from todos where id = :id")
                .param("id", UUID.fromString(t.get("id").asText())).query(UUID.class).single();
        assertThat(ownerAfter).isEqualTo(aliceId);
        assertThat(call(get("/api/pets/" + bobPet + "/todos"), bob, 200).get("todos")).isEmpty();
    }
}
