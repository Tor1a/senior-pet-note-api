package com.oraegyeot.seniorpet.visitquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 소유자 격리: 다른 사용자의 반려동물·질문에 접근하면 404, 본문의 userId·petId 는 무시(보안 규칙 1~7). */
class VisitQuestionOwnershipTest extends ApiTestSupport {

    private static void assertNotFound(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("NOT_FOUND");
    }

    private JsonNode createQuestion(String token, String pet, String text) throws Exception {
        return call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", text)), token, 201);
    }

    @Test
    void 남의_반려동물로_등록하거나_목록을_보면_404() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        createPet(bob);

        assertNotFound(call(jsonPost("/api/pets/" + alicePet + "/visit-questions", Map.of("text", "침입")), bob, 404));
        for (String status : new String[] {"", "?status=done", "?status=all"}) {
            assertNotFound(call(get("/api/pets/" + alicePet + "/visit-questions" + status), bob, 404));
        }
        assertNotFound(call(get("/api/pets/" + UUID.randomUUID() + "/visit-questions"), bob, 404));
        assertNotFound(call(get("/api/pets/not-a-uuid/visit-questions"), bob, 404));
        assertThat(call(get("/api/pets/" + alicePet + "/visit-questions"), alice, 200).get("questions")).isEmpty();
    }

    @Test
    void 남의_질문_수정_완료_삭제는_404이고_원본은_그대로() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        createPet(bob);
        String id = createQuestion(alice, alicePet, "앨리스 질문").get("id").asText();

        assertNotFound(call(jsonPut("/api/visit-questions/" + id, Map.of("text", "바꿈")), bob, 404));
        assertNotFound(call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", true)), bob, 404));
        assertNotFound(call(delete("/api/visit-questions/" + id), bob, 404));
        // 없는 id, UUID 형식이 아닌 id
        assertNotFound(call(jsonPut("/api/visit-questions/" + UUID.randomUUID(), Map.of("text", "a")), bob, 404));
        assertNotFound(call(jsonPut("/api/visit-questions/abc", Map.of("text", "a")), bob, 404));
        assertNotFound(call(jsonPut("/api/visit-questions/abc/completion", Map.of("completed", true)), bob, 404));
        assertNotFound(call(delete("/api/visit-questions/abc"), bob, 404));

        JsonNode mine = call(get("/api/pets/" + alicePet + "/visit-questions"), alice, 200).get("questions");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("text").asText()).isEqualTo("앨리스 질문");
        assertThat(mine.get(0).get("completed").asBoolean()).isFalse();
    }

    @Test
    void 목록은_사용자별로_격리된다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String alicePet = createPet(alice);
        String bobPet = createPet(bob);
        createQuestion(alice, alicePet, "앨리스 것");
        createQuestion(bob, bobPet, "밥 것");

        JsonNode a = call(get("/api/pets/" + alicePet + "/visit-questions?status=all"), alice, 200).get("questions");
        JsonNode b = call(get("/api/pets/" + bobPet + "/visit-questions?status=all"), bob, 200).get("questions");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).get("text").asText()).isEqualTo("앨리스 것");
        assertThat(b).hasSize(1);
        assertThat(b.get(0).get("text").asText()).isEqualTo("밥 것");
    }

    @Test
    void 본문의_userId와_petId는_무시되고_로그인_사용자로_저장된다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        UUID aliceId = userIdOf(alice);
        UUID bobId = userIdOf(bob);
        String alicePet = createPet(alice);
        String bobPet = createPet(bob);

        JsonNode q = call(jsonPost("/api/pets/" + alicePet + "/visit-questions",
                Map.of("text", "질문", "userId", bobId.toString(), "petId", bobPet)), alice, 201);
        assertThat(q.get("petId").asText()).isEqualTo(alicePet);
        UUID id = UUID.fromString(q.get("id").asText());
        assertThat(jdbc.sql("select user_id from visit_questions where id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(aliceId);

        call(jsonPut("/api/visit-questions/" + id,
                Map.of("text", "질문2", "userId", bobId.toString(), "petId", bobPet)), alice, 200);
        assertThat(jdbc.sql("select user_id from visit_questions where id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(aliceId);
        assertThat(jdbc.sql("select pet_id from visit_questions where id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(UUID.fromString(alicePet));
        assertThat(call(get("/api/pets/" + bobPet + "/visit-questions?status=all"), bob, 200).get("questions")).isEmpty();
    }

    @Test
    void 저장소는_OwnedRepository를_상속한다() {
        assertThat(OwnedRepository.class).isAssignableFrom(VisitQuestionRepository.class);
        for (var m : VisitQuestionRepository.class.getDeclaredMethods()) {
            assertThat(m.getName()).as("userId 조건이 없는 메서드 금지").containsAnyOf("ByUserId", "AndUserId");
        }
    }
}
