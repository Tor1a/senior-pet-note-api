package com.oraegyeot.seniorpet.visitquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 사용자당 30개 한도(완료 포함)와 동시 등록 직렬화(advisory lock). */
class VisitQuestionLimitTest extends ApiTestSupport {

    private String token;
    private String pet;

    private void setUp() throws Exception {
        setSeoulTime(2026, 10, 15, 12, 0);
        token = newDisposableUserToken();
        pet = createPet(token);
    }

    private JsonNode post(String text, int expected) throws Exception {
        return call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", text)), token, expected);
    }

    private void fill(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            post("질문 " + i, 201);
        }
    }

    private long count() {
        return jdbc.sql("select count(*) from visit_questions where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Long.class).single();
    }

    @Test
    void 서른번째는_성공하고_서른한번째는_409() throws Exception {
        setUp();
        fill(30);
        JsonNode err = post("넘침", 409);
        assertThat(err.get("code").asText()).isEqualTo("VISIT_QUESTION_LIMIT_REACHED");
        assertThat(err.get("message").asText()).contains("30개");
        assertThat(count()).isEqualTo(30);
    }

    @Test
    void 완료한_항목도_한도에_포함되고_삭제하면_다시_등록된다() throws Exception {
        setUp();
        fill(30);
        JsonNode list = call(get("/api/pets/" + pet + "/visit-questions"), token, 200).get("questions");
        String first = list.get(0).get("id").asText();
        call(jsonPut("/api/visit-questions/" + first + "/completion", Map.of("completed", true)), token, 200);
        post("넘침", 409); // 완료해도 한도는 그대로

        call(delete("/api/visit-questions/" + first), token, 204);
        post("다시", 201);
        post("또", 409);
    }

    @Test
    void 한도_상태에서도_수정_완료_취소_삭제는_된다() throws Exception {
        setUp();
        fill(30);
        String id = call(get("/api/pets/" + pet + "/visit-questions"), token, 200).get("questions").get(0)
                .get("id").asText();
        call(jsonPut("/api/visit-questions/" + id, Map.of("text", "고침")), token, 200);
        call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", true)), token, 200);
        call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", false)), token, 200);
        call(delete("/api/visit-questions/" + id), token, 204);
        assertThat(count()).isEqualTo(29);
    }

    @Test
    void 한도는_사용자별이다() throws Exception {
        setUp();
        fill(30);
        String other = newDisposableUserToken();
        String otherPet = createPet(other);
        call(jsonPost("/api/pets/" + otherPet + "/visit-questions", Map.of("text", "다른 사람")), other, 201);
    }

    @Test
    void 동시_등록이_겹쳐도_30개를_넘지_않는다() throws Exception {
        setUp();
        fill(27);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int n = i;
                Callable<Integer> task = () -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", "동시 " + n))
                            .header("Authorization", bearer(token))).andReturn().getResponse().getStatus();
                };
                fs.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            int created = 0;
            int conflict = 0;
            for (Future<Integer> f : fs) {
                int st = f.get();
                if (st == 201) {
                    created++;
                } else {
                    assertThat(st).isEqualTo(409);
                    conflict++;
                }
            }
            assertThat(created).isEqualTo(3);
            assertThat(conflict).isEqualTo(threads - 3);
            assertThat(count()).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
    }
}
