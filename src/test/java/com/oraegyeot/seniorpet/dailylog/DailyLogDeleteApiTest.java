package com.oraegyeot.seniorpet.dailylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 일일 기록 삭제 API 통합 테스트 (docs/api-undo.md). */
class DailyLogDeleteApiTest extends ApiTestSupport {

    private static final Map<String, Object> BODY = Map.of("foodLevel", 2, "waterMl", 300,
            "symptoms", List.of(), "symptomsNone", true, "memo", "메모");

    private String url(String pet, String date) {
        return "/api/pets/" + pet + "/daily-logs/" + date;
    }

    private void put(String t, String pet, String date) throws Exception {
        call(jsonPut(url(pet, date), BODY), t, 200);
    }

    private JsonNode todayOf(String t, String pet) throws Exception {
        return call(get("/api/pets/" + pet + "/today"), t, 200);
    }

    private JsonNode historyDay(String t, String pet, String from, String to, String date) throws Exception {
        JsonNode res = call(get("/api/pets/" + pet + "/daily-logs?from=" + from + "&to=" + to), t, 200);
        for (JsonNode d : res.get("days")) {
            if (d.get("recordDate").asText().equals(date)) {
                return d;
            }
        }
        throw new AssertionError("날짜 없음: " + date);
    }

    private int count(String pet) {
        return jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single();
    }

    @Test
    void 오늘_기록_삭제하면_today와_history에서_사라지고_투약_체크는_그대로다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("08:00"));
        setSeoulTime(2026, 10, 12, 12, 0);
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), t, 201);
        put(t, pet, "2026-10-12");
        assertThat(todayOf(t, pet).get("dailyLog").isNull()).isFalse();
        assertThat(historyDay(t, pet, "2026-10-12", "2026-10-12", "2026-10-12").get("dailyLog").isNull()).isFalse();

        mvc.perform(delete(url(pet, "2026-10-12")).header("Authorization", bearer(t)))
                .andExpect(status().isNoContent());

        JsonNode today = todayOf(t, pet);
        assertThat(today.get("dailyLog").isNull()).isTrue();
        assertThat(today.get("suggestions").isObject()).isTrue();
        assertThat(today.get("doses").get(0).get("taken").asBoolean()).isTrue();
        JsonNode day = historyDay(t, pet, "2026-10-12", "2026-10-12", "2026-10-12");
        assertThat(day.get("dailyLog").isNull()).isTrue();
        assertThat(day.get("medication").get("takenCount").asInt()).isEqualTo(1);
        assertThat(count(pet)).isZero();
    }

    @Test
    void 기록이_없어도_204이고_연속_두_번도_204() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        call(delete(url(pet, "2026-10-12")), t, 204);
        call(delete(url(pet, "2026-10-12")), t, 204);
        put(t, pet, "2026-10-12");
        call(delete(url(pet, "2026-10-12")), t, 204);
        call(delete(url(pet, "2026-10-12")), t, 204);
        assertThat(count(pet)).isZero();
    }

    @Test
    void 새벽_4시_경계는_PUT과_같은_기록_날짜를_쓴다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 3, 59);
        put(t, pet, "2026-10-11");
        assertThat(call(delete(url(pet, "2026-10-12")), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        assertThat(count(pet)).isEqualTo(1);
        call(delete(url(pet, "2026-10-11")), t, 204);
        assertThat(count(pet)).isZero();

        put(t, pet, "2026-10-11");
        setSeoulTime(2026, 10, 12, 4, 0);
        assertThat(call(delete(url(pet, "2026-10-11")), t, 400).get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        assertThat(count(pet)).isEqualTo(1);
        put(t, pet, "2026-10-12");
        call(delete(url(pet, "2026-10-12")), t, 204);
        assertThat(count(pet)).isEqualTo(1); // 전날 기록은 남는다
    }

    @Test
    void 과거_미래_날짜와_형식_오류는_400이고_데이터는_유지() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        put(t, pet, "2026-10-12");
        for (String d : List.of("2026-10-11", "2026-10-13", "2020-01-01", "2026-13-01", "abc", "20261012")) {
            JsonNode err = call(delete(url(pet, d)), t, 400);
            assertThat(err.get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
            assertThat(err.get("message").asText()).contains("2026-10-12");
        }
        assertThat(count(pet)).isEqualTo(1);
    }

    @Test
    void 소유권_인증_검증_순서() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String pet = createPet(alice);
        setSeoulTime(2026, 10, 12, 12, 0);
        put(alice, pet, "2026-10-12");
        // 남의 pet 은 날짜가 틀려도 404 (소유권이 먼저)
        assertThat(call(delete(url(pet, "2026-10-12")), bob, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(delete(url(pet, "2020-01-01")), bob, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(delete(url(pet, "abc")), bob, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(delete(url(UUID.randomUUID().toString(), "2026-10-12")), bob, 404).get("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(count(pet)).isEqualTo(1);
        assertThat(todayOf(alice, pet).get("dailyLog").isNull()).isFalse();
        mvc.perform(delete(url(pet, "2026-10-12"))).andExpect(status().isUnauthorized());
        assertThat(count(pet)).isEqualTo(1);
    }

    @Test
    void 삭제는_다른_날짜와_다른_사용자_기록에_영향이_없다() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String petA = createPet(alice);
        String petB = createPet(bob);
        setSeoulTime(2026, 10, 11, 12, 0);
        put(alice, petA, "2026-10-11");
        setSeoulTime(2026, 10, 12, 12, 0);
        put(alice, petA, "2026-10-12");
        put(bob, petB, "2026-10-12");

        call(delete(url(petA, "2026-10-12")), alice, 204);

        assertThat(historyDay(alice, petA, "2026-10-11", "2026-10-12", "2026-10-11").get("dailyLog").isNull()).isFalse();
        assertThat(historyDay(alice, petA, "2026-10-11", "2026-10-12", "2026-10-12").get("dailyLog").isNull()).isTrue();
        assertThat(todayOf(bob, petB).get("dailyLog").isNull()).isFalse();
    }

    @Test
    void 삭제_후_같은_날짜로_다시_저장할_수_있다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        put(t, pet, "2026-10-12");
        call(delete(url(pet, "2026-10-12")), t, 204);
        call(jsonPut(url(pet, "2026-10-12"), Map.of("foodLevel", 3, "symptoms", List.of(), "symptomsNone", false)), t, 200);
        JsonNode log = todayOf(t, pet).get("dailyLog");
        assertThat(log.get("foodLevel").asInt()).isEqualTo(3);
        assertThat(count(pet)).isEqualTo(1);
    }

    @Test
    void 동시_삭제_두_건은_모두_204() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);
        put(t, pet, "2026-10-12");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> mvc.perform(delete(url(pet, "2026-10-12"))
                    .header("Authorization", bearer(t))).andReturn().getResponse().getStatus();
            Future<Integer> a = pool.submit(task);
            Future<Integer> b = pool.submit(task);
            assertThat(a.get()).isEqualTo(204);
            assertThat(b.get()).isEqualTo(204);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(pet)).isZero();
    }
}
