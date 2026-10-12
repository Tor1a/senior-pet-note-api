package com.oraegyeot.seniorpet.dailylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 저장 되돌리기 QA 보강: 04시 초 단위 경계, 재저장·재삭제, PUT/DELETE 동시성, updatedAt 정합(연속 수정·다른 날짜·history). */
class DailyLogUndoEdgeQaTest extends ApiTestSupport {

    private static final int ROUNDS = 40;

    private String url(String pet, String date) {
        return "/api/pets/" + pet + "/daily-logs/" + date;
    }

    private Map<String, Object> body(int food) {
        return Map.of("foodLevel", food, "symptoms", List.of(), "symptomsNone", true);
    }

    private void setSeoul(int y, int mo, int d, int h, int mi, int s) {
        clock.set(ZonedDateTime.of(y, mo, d, h, mi, s, 0, SEOUL).toInstant());
    }

    private int count(String pet) {
        return jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single();
    }

    @Test
    void 초_단위_경계_03시59분59초는_전날_04시00분00초는_당일() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoul(2026, 10, 12, 3, 59, 59);
        call(jsonPut(url(pet, "2026-10-11"), body(1)), t, 200);
        assertThat(call(delete(url(pet, "2026-10-12")), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        call(delete(url(pet, "2026-10-11")), t, 204);
        assertThat(count(pet)).isZero();

        call(jsonPut(url(pet, "2026-10-11"), body(1)), t, 200);
        setSeoul(2026, 10, 12, 4, 0, 0);
        assertThat(call(delete(url(pet, "2026-10-11")), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        assertThat(count(pet)).isEqualTo(1);
        call(delete(url(pet, "2026-10-12")), t, 204); // 당일은 기록이 없어도 204
        assertThat(count(pet)).isEqualTo(1);
    }

    @Test
    void 삭제_후_재저장은_새_id이고_재삭제도_204_그리고_today_history에_반영() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        String id1 = call(jsonPut(url(pet, "2026-10-12"), body(1)), t, 200).get("id").asText();
        call(delete(url(pet, "2026-10-12")), t, 204);
        JsonNode second = call(jsonPut(url(pet, "2026-10-12"), body(2)), t, 200);
        assertThat(second.get("id").asText()).isNotEqualTo(id1);
        assertThat(call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog").get("id").asText())
                .isEqualTo(second.get("id").asText());
        call(delete(url(pet, "2026-10-12")), t, 204);
        call(delete(url(pet, "2026-10-12")), t, 204);
        assertThat(call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog").isNull()).isTrue();
        JsonNode hist = call(get("/api/pets/" + pet + "/daily-logs?from=2026-10-12&to=2026-10-12"), t, 200);
        assertThat(hist.get("days").get(0).get("dailyLog").isNull()).isTrue();
    }

    @Test
    void 남의_pet_은_04시_경계_전후로_날짜가_틀려도_404() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String pet = createPet(alice);
        setSeoul(2026, 10, 12, 3, 59, 59);
        call(jsonPut(url(pet, "2026-10-11"), body(1)), alice, 200);
        for (String d : List.of("2026-10-11", "2026-10-12", "2026-10-13", "x")) {
            assertThat(call(delete(url(pet, d)), bob, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        }
        setSeoul(2026, 10, 12, 4, 0, 0);
        for (String d : List.of("2026-10-11", "2026-10-12")) {
            assertThat(call(delete(url(pet, d)), bob, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        }
        assertThat(count(pet)).isEqualTo(1);
    }

    /** 동시 PUT 과 DELETE 가 겹쳐도 5xx 가 없고 최종 상태는 1건 또는 0건이다(PUT 은 낙관적 락 실패 시 재시도). */
    @Test
    void 동시_PUT과_DELETE가_겹쳐도_5xx_없다() throws Exception {
        raceRounds(true);
    }

    private void raceRounds(boolean strictNo5xx) throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int present = 0;
        int absent = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                if (round % 2 == 0) {
                    call(jsonPut(url(pet, "2026-10-12"), body(1)), t, 200);
                }
                Callable<Integer> put = () -> mvc.perform(jsonPut(url(pet, "2026-10-12"), body(3))
                        .header("Authorization", bearer(t))).andReturn().getResponse().getStatus();
                Callable<Integer> del = () -> mvc.perform(delete(url(pet, "2026-10-12"))
                        .header("Authorization", bearer(t))).andReturn().getResponse().getStatus();
                List<Future<Integer>> fs = new ArrayList<>();
                fs.add(pool.submit(put));
                fs.add(pool.submit(del));
                fs.add(pool.submit(put));
                fs.add(pool.submit(del));
                for (Future<Integer> f : fs) {
                    int st = f.get();
                    if (strictNo5xx) {
                        assertThat(st).as("라운드 %d 상태", round).isIn(200, 204);
                    } else {
                        assertThat(st).as("라운드 %d 상태", round).isIn(200, 204, 500);
                    }
                }
                int n = count(pet);
                assertThat(n).isIn(0, 1);
                JsonNode today = call(get("/api/pets/" + pet + "/today"), t, 200);
                // 서버 상태가 today 응답과 DB 행 수와 일치
                assertThat(today.get("dailyLog").isNull()).isEqualTo(n == 0);
                if (n == 1) {
                    assertThat(today.get("dailyLog").get("foodLevel").asInt()).isIn(1, 3);
                    present++;
                } else {
                    absent++;
                }
                call(delete(url(pet, "2026-10-12")), t, 204);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(present + absent).isEqualTo(ROUNDS);
    }

    @Test
    void 수정_연속_5회_모두_저장_응답_updatedAt_이_today_와_history_와_같다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        String prev = null;
        for (int i = 1; i <= 6; i++) { // 첫 저장 + 수정 5회
            String saved = call(jsonPut(url(pet, "2026-10-12"), body(i % 3 + 1)), t, 200).get("updatedAt").asText();
            JsonNode today = call(get("/api/pets/" + pet + "/today"), t, 200);
            assertThat(today.get("dailyLog").get("updatedAt").asText()).as("today %d회", i).isEqualTo(saved);
            JsonNode hist = call(get("/api/pets/" + pet + "/daily-logs?from=2026-10-12&to=2026-10-12"), t, 200);
            assertThat(hist.get("days").get(0).get("dailyLog").get("updatedAt").asText()).as("history %d회", i)
                    .isEqualTo(saved);
            if (prev != null) {
                assertThat(saved).as("수정마다 updatedAt 이 달라진다").isNotEqualTo(prev);
            }
            prev = saved;
        }
    }

    @Test
    void 서로_다른_기록_날짜에서도_updatedAt_이_일치한다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoul(2026, 10, 12, 3, 59, 0); // 기록 날짜 10-11
        String a = call(jsonPut(url(pet, "2026-10-11"), body(1)), t, 200).get("updatedAt").asText();
        assertThat(call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog").get("updatedAt").asText()).isEqualTo(a);
        String a2 = call(jsonPut(url(pet, "2026-10-11"), body(2)), t, 200).get("updatedAt").asText();
        assertThat(call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog").get("updatedAt").asText()).isEqualTo(a2);

        setSeoul(2026, 10, 12, 4, 0, 0); // 기록 날짜 10-12
        String b = call(jsonPut(url(pet, "2026-10-12"), body(3)), t, 200).get("updatedAt").asText();
        JsonNode today = call(get("/api/pets/" + pet + "/today"), t, 200);
        assertThat(today.get("recordDate").asText()).isEqualTo("2026-10-12");
        assertThat(today.get("dailyLog").get("updatedAt").asText()).isEqualTo(b);
        // 전날 기록은 건드려지지 않았다
        JsonNode hist = call(get("/api/pets/" + pet + "/daily-logs?from=2026-10-11&to=2026-10-12"), t, 200);
        for (JsonNode d : hist.get("days")) {
            String expect = d.get("recordDate").asText().equals("2026-10-11") ? a2 : b;
            assertThat(d.get("dailyLog").get("updatedAt").asText()).isEqualTo(expect);
        }
    }
}
