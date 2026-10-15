package com.oraegyeot.seniorpet.visitquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 진료 때 물어볼 것 API (docs/api-visit-questions.md). 시계는 2026-10-15 12:00 서울로 고정하고 필요하면 앞으로 보낸다. */
class VisitQuestionApiTest extends ApiTestSupport {

    private String token;
    private String pet;

    private void setUp() throws Exception {
        setSeoulTime(2026, 10, 15, 12, 0);
        token = newDisposableUserToken();
        pet = createPet(token);
    }

    private JsonNode post(String text, int expected) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("text", text);
        return call(jsonPost("/api/pets/" + pet + "/visit-questions", b), token, expected);
    }

    /** 시계를 1분 앞으로 보내고 등록한다(createdAt 순서를 확정하기 위함) */
    private String add(String text) throws Exception {
        clock.set(clock.instant().plusSeconds(60));
        return post(text, 201).get("id").asText();
    }

    private void complete(String id, boolean completed) throws Exception {
        clock.set(clock.instant().plusSeconds(60));
        call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", completed)), token, 200);
    }

    private List<String> texts(String query) throws Exception {
        List<String> out = new ArrayList<>();
        call(get("/api/pets/" + pet + "/visit-questions" + query), token, 200).get("questions")
                .forEach(n -> out.add(n.get("text").asText()));
        return out;
    }

    private static void assertValidation(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 등록하면_201이고_계약_필드가_그대로_돌아온다() throws Exception {
        setUp();
        JsonNode q = post("  약을 식전에 줘도 되는지 물어보기  ", 201);
        assertThat(UUID.fromString(q.get("id").asText())).isNotNull();
        assertThat(q.get("petId").asText()).isEqualTo(pet);
        assertThat(q.get("text").asText()).isEqualTo("약을 식전에 줘도 되는지 물어보기"); // 앞뒤 공백 제거
        assertThat(q.get("completed").asBoolean()).isFalse();
        assertThat(q.get("completedAt").isNull()).isTrue();
        assertThat(Instant.parse(q.get("createdAt").asText())).isNotNull();
        assertThat(Instant.parse(q.get("updatedAt").asText())).isNotNull();
        assertThat(q.size()).isEqualTo(7);
    }

    @Test
    void 왕복_수정_완료_취소_삭제() throws Exception {
        setUp();
        String id = add("처음 글");
        JsonNode edited = call(jsonPut("/api/visit-questions/" + id, Map.of("text", "고친 글")), token, 200);
        assertThat(edited.get("id").asText()).isEqualTo(id);
        assertThat(edited.get("text").asText()).isEqualTo("고친 글");

        complete(id, true);
        // PUT 은 완료 상태를 바꾸지 않는다
        JsonNode after = call(jsonPut("/api/visit-questions/" + id, Map.of("text", "또 고침")), token, 200);
        assertThat(after.get("completed").asBoolean()).isTrue();
        assertThat(after.get("completedAt").isNull()).isFalse();

        complete(id, false);
        assertThat(texts("")).containsExactly("또 고침");

        call(delete("/api/visit-questions/" + id), token, 204);
        assertThat(texts("?status=all")).isEmpty();
        assertThat(jdbc.sql("select count(*) from visit_questions where id = :id").param("id", UUID.fromString(id))
                .query(Long.class).single()).isZero(); // 실제 삭제
    }

    @Test
    void 완료는_멱등이고_취소_뒤_재완료는_시각이_갱신된다() throws Exception {
        setUp();
        String id = add("질문");
        String url = "/api/visit-questions/" + id + "/completion";
        JsonNode first = call(jsonPut(url, Map.of("completed", true)), token, 200);
        assertThat(first.get("completed").asBoolean()).isTrue();
        clock.set(clock.instant().plusSeconds(600));
        JsonNode again = call(jsonPut(url, Map.of("completed", true)), token, 200);
        assertThat(again.get("completedAt").asText()).isEqualTo(first.get("completedAt").asText());

        JsonNode reopened = call(jsonPut(url, Map.of("completed", false)), token, 200);
        assertThat(reopened.get("completed").asBoolean()).isFalse();
        assertThat(reopened.get("completedAt").isNull()).isTrue();
        assertThat(call(jsonPut(url, Map.of("completed", false)), token, 200).get("completed").asBoolean()).isFalse();

        clock.set(clock.instant().plusSeconds(600));
        JsonNode redone = call(jsonPut(url, Map.of("completed", true)), token, 200);
        assertThat(Instant.parse(redone.get("completedAt").asText()))
                .isAfter(Instant.parse(first.get("completedAt").asText()));
    }

    @Test
    void completion_본문에_completed가_없으면_400() throws Exception {
        setUp();
        String id = add("질문");
        assertValidation(call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of()), token, 400));
    }

    @Test
    void 글자_수_검증은_코드_포인트_기준() throws Exception {
        setUp();
        post("가".repeat(100), 201);
        post("😀".repeat(100), 201); // 이모지 1자
        assertValidation(post("가".repeat(101), 400));
        assertValidation(post("😀".repeat(101), 400));
        assertValidation(post("", 400));
        assertValidation(post("   \t ", 400));
        assertValidation(post(null, 400));
        assertValidation(call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of()), token, 400));
        // 공백 제거 뒤 100자면 허용
        post("  " + "나".repeat(100) + "  ", 201);
    }

    @Test
    void NUL_문자가_있으면_400이고_입력을_되돌려주지_않는다() throws Exception {
        setUp();
        JsonNode err = post("앞\u0000뒤비밀", 400);
        assertValidation(err);
        assertThat(err.toString()).doesNotContain("비밀").doesNotContain("\\u0000");
        String id = add("질문");
        assertValidation(call(jsonPut("/api/visit-questions/" + id, Map.of("text", "고\u0000침")), token, 400));
        assertThat(texts("")).containsExactly("질문");
    }

    @Test
    void 수정도_같은_검증을_한다() throws Exception {
        setUp();
        String id = add("질문");
        assertValidation(call(jsonPut("/api/visit-questions/" + id, Map.of("text", " ")), token, 400));
        assertValidation(call(jsonPut("/api/visit-questions/" + id, Map.of("text", "가".repeat(101))), token, 400));
        assertValidation(call(jsonPut("/api/visit-questions/" + id, Map.of()), token, 400));
        assertThat(texts("")).containsExactly("질문");
    }

    @Test
    void 한글_이모지_줄바꿈_글이_무손실로_왕복한다() throws Exception {
        setUp();
        String text = "약 먹일 때 😀 자꾸 뱉어요\n어제부터 \"그래요\" <b>& 확인";
        JsonNode q = post(text, 201);
        assertThat(q.get("text").asText()).isEqualTo(text);
        assertThat(texts("")).containsExactly(text);
        assertThat(jdbc.sql("select text from visit_questions where id = :id").param("id", UUID.fromString(q.get("id").asText()))
                .query(String.class).single()).isEqualTo(text);
    }

    @Test
    void status_필터와_정렬() throws Exception {
        setUp();
        String a = add("A");
        String b = add("B");
        String c = add("C");
        String d = add("D");
        complete(b, true); // 먼저 완료
        complete(d, true); // 나중 완료

        assertThat(texts("")).containsExactly("A", "C");                 // 생략 = open, 등록 순
        assertThat(texts("?status=open")).containsExactly("A", "C");
        assertThat(texts("?status=done")).containsExactly("D", "B");     // 최근 완료 먼저
        assertThat(texts("?status=all")).containsExactly("A", "C", "D", "B"); // open 먼저 + done 뒤
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void 잘못된_status는_400() throws Exception {
        setUp();
        for (String q : List.of("?status=", "?status=OPEN", "?status=All", "?status=other")) {
            assertValidation(call(get("/api/pets/" + pet + "/visit-questions" + q), token, 400));
        }
    }

    @Test
    void 응답_updatedAt은_이어서_조회한_값과_같다() throws Exception {
        setUp();
        JsonNode created = post("질문", 201);
        JsonNode listed = call(get("/api/pets/" + pet + "/visit-questions"), token, 200).get("questions").get(0);
        assertThat(created.get("updatedAt").asText()).isEqualTo(listed.get("updatedAt").asText());

        clock.set(clock.instant().plusSeconds(5));
        String id = created.get("id").asText();
        String saved = call(jsonPut("/api/visit-questions/" + id, Map.of("text", "고침")), token, 200)
                .get("updatedAt").asText();
        assertThat(call(get("/api/pets/" + pet + "/visit-questions"), token, 200).get("questions").get(0)
                .get("updatedAt").asText()).isEqualTo(saved);
        clock.set(clock.instant().plusSeconds(5));
        String done = call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", true)), token, 200)
                .get("updatedAt").asText();
        assertThat(call(get("/api/pets/" + pet + "/visit-questions?status=done"), token, 200).get("questions").get(0)
                .get("updatedAt").asText()).isEqualTo(done);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        setUp();
        mvc.perform(get("/api/pets/" + pet + "/visit-questions")).andExpect(status().isUnauthorized());
        mvc.perform(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", "a")))
                .andExpect(status().isUnauthorized());
        mvc.perform(jsonPut("/api/visit-questions/" + UUID.randomUUID(), Map.of("text", "a")))
                .andExpect(status().isUnauthorized());
        mvc.perform(jsonPut("/api/visit-questions/" + UUID.randomUUID() + "/completion", Map.of("completed", true)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/visit-questions/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void 푸시와_이벤트는_만들지_않는다() throws Exception {
        setUp();
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "android");
        UUID userId = userIdOf(token);
        String id = add("질문");
        complete(id, true);
        assertThat(pushSender.sentTo(fcm)).isEmpty();
        assertThat(jdbc.sql("select count(*) from events where user_id = :u").param("u", userId)
                .query(Long.class).single()).isZero();
    }
}
