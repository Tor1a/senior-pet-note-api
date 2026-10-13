package com.oraegyeot.seniorpet.dailylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 일일 기록의 루틴 기록(산책·배변 횟수) 통합 테스트 (docs/api-routine.md). */
class DailyLogRoutineApiTest extends ApiTestSupport {

    private static final String DATE = "2026-10-12";

    private String url(String pet) {
        return "/api/pets/" + pet + "/daily-logs/" + DATE;
    }

    private Map<String, Object> body(Object walk, Object poop) {
        Map<String, Object> b = new HashMap<>();
        b.put("foodLevel", 2);
        b.put("memo", "메모");
        b.put("walkCount", walk);
        b.put("poopCount", poop);
        return b;
    }

    /** 기존 8필드만 담은 구버전 본문(루틴 필드 생략) */
    private Map<String, Object> legacyBody(int food) {
        return Map.of("foodLevel", food, "symptoms", List.of(), "symptomsNone", true);
    }

    private JsonNode today(String t, String pet) throws Exception {
        return call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog");
    }

    private JsonNode rawPut(String t, String pet, String json, int expected) throws Exception {
        return call(put(url(pet)).contentType(MediaType.APPLICATION_JSON).content(json), t, expected);
    }

    @Test
    void 값을_저장하면_응답_today_history에_왕복된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        JsonNode res = call(jsonPut(url(pet), body(2, 3)), t, 200);
        assertThat(res.get("walkCount").asInt()).isEqualTo(2);
        assertThat(res.get("poopCount").asInt()).isEqualTo(3);
        assertThat(today(t, pet).get("walkCount").asInt()).isEqualTo(2);
        assertThat(today(t, pet).get("poopCount").asInt()).isEqualTo(3);

        JsonNode hist = call(get("/api/pets/" + pet + "/daily-logs?from=" + DATE + "&to=" + DATE), t, 200);
        JsonNode log = hist.get("days").get(0).get("dailyLog");
        assertThat(log.get("walkCount").asInt()).isEqualTo(2);
        assertThat(log.get("poopCount").asInt()).isEqualTo(3);
    }

    @Test
    void 영은_저장되고_안_적음과_구분된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        JsonNode res = call(jsonPut(url(pet), body(0, null)), t, 200);
        assertThat(res.get("walkCount").isNull()).isFalse();
        assertThat(res.get("walkCount").asInt()).isZero();
        assertThat(res.get("poopCount").isNull()).isTrue();
        assertThat(jdbc.sql("select walk_count from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single()).isZero();
    }

    @Test
    void 필드를_생략하면_기존_값이_유지된다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(2, 3)), t, 200);

        JsonNode res = call(jsonPut(url(pet), legacyBody(3)), t, 200); // 구버전 본문
        assertThat(res.get("foodLevel").asInt()).isEqualTo(3);
        assertThat(res.get("memo").isNull()).isTrue(); // 기존 8필드는 전체 교체
        assertThat(res.get("walkCount").asInt()).isEqualTo(2);
        assertThat(res.get("poopCount").asInt()).isEqualTo(3);
        assertThat(today(t, pet).get("walkCount").asInt()).isEqualTo(2);
    }

    @Test
    void 한쪽만_생략하면_그쪽만_유지되고_보낸_쪽은_바뀐다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(2, 3)), t, 200);

        JsonNode res = rawPut(t, pet, "{\"walkCount\":5}", 200);
        assertThat(res.get("walkCount").asInt()).isEqualTo(5);
        assertThat(res.get("poopCount").asInt()).isEqualTo(3);
        res = rawPut(t, pet, "{\"poopCount\":null}", 200);
        assertThat(res.get("walkCount").asInt()).isEqualTo(5);
        assertThat(res.get("poopCount").isNull()).isTrue();
    }

    @Test
    void 명시적_null은_지운다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(2, 3)), t, 200);
        JsonNode res = rawPut(t, pet, "{\"walkCount\":null,\"poopCount\":null}", 200);
        assertThat(res.get("walkCount").isNull()).isTrue();
        assertThat(res.get("poopCount").isNull()).isTrue();
        assertThat(today(t, pet).get("walkCount").isNull()).isTrue();
    }

    @Test
    void 행이_없을때_생략하면_null이다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        JsonNode res = call(jsonPut(url(pet), legacyBody(2)), t, 200);
        assertThat(res.has("walkCount")).isTrue();
        assertThat(res.get("walkCount").isNull()).isTrue();
        assertThat(res.get("poopCount").isNull()).isTrue();
    }

    @Test
    void 경계값_0과_20은_통과하고_범위_밖과_소수는_400() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        assertThat(call(jsonPut(url(pet), body(20, 0)), t, 200).get("walkCount").asInt()).isEqualTo(20);
        for (String bad : List.of("21", "-1", "1.5", "2.5", "100000000000", "\"abc\"")) {
            JsonNode err = rawPut(t, pet, "{\"walkCount\":" + bad + "}", 400);
            assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
            err = rawPut(t, pet, "{\"poopCount\":" + bad + "}", 400);
            assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        }
        // 실패한 요청은 기존 값을 바꾸지 않는다
        assertThat(today(t, pet).get("walkCount").asInt()).isEqualTo(20);
    }

    @Test
    void 기존_필드_검증은_그대로다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        Map<String, Object> b = body(1, 1);
        b.put("foodLevel", 4);
        assertThat(call(jsonPut(url(pet), b), t, 400).get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single()).isZero();
    }

    @Test
    void 기록_날짜_규칙은_새벽_4시_경계에서_그대로다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 13, 3, 59); // 아직 10-12
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-12", body(1, 1)), t, 200);
        JsonNode err = call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-13", body(1, 1)), t, 400);
        assertThat(err.get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        setSeoulTime(2026, 10, 13, 4, 0); // 10-13 시작
        err = call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-12", body(2, 2)), t, 400);
        assertThat(err.get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-13", body(2, 2)), t, 200);
        // 지난 날 값은 바뀌지 않았다
        assertThat(jdbc.sql("select walk_count from daily_logs where pet_id = :p and record_date = '2026-10-12'")
                .param("p", UUID.fromString(pet)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void 루틴만_있는_행도_today와_history에_나온다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), Map.of("walkCount", 1)), t, 200);
        JsonNode log = today(t, pet);
        assertThat(log.isNull()).isFalse();
        assertThat(log.get("foodLevel").isNull()).isTrue();
        assertThat(log.get("walkCount").asInt()).isEqualTo(1);
    }

    @Test
    void 되돌리기_복원_본문_10필드로_PUT하면_저장_전_값으로_돌아간다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        JsonNode before = call(jsonPut(url(pet), body(2, null)), t, 200);
        call(jsonPut(url(pet), body(7, 8)), t, 200);

        Map<String, Object> restore = new HashMap<>();
        restore.put("foodLevel", before.get("foodLevel").asInt());
        restore.put("waterLevel", null);
        restore.put("waterMl", null);
        restore.put("weightKg", null);
        restore.put("symptoms", List.of());
        restore.put("symptomsNone", false);
        restore.put("symptomOther", null);
        restore.put("memo", "메모");
        restore.put("walkCount", before.get("walkCount").asInt());
        restore.put("poopCount", null);
        JsonNode res = call(jsonPut(url(pet), restore), t, 200);
        assertThat(res.get("walkCount").asInt()).isEqualTo(2);
        assertThat(res.get("poopCount").isNull()).isTrue(); // 저장 전 null → 명시 null 로 지움
    }

    @Test
    void 일일_기록_삭제하면_루틴_값도_함께_사라진다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(2, 3)), t, 200);
        mvc.perform(delete(url(pet)).header("Authorization", bearer(t))).andExpect(status().isNoContent());
        assertThat(today(t, pet).isNull()).isTrue();
        assertThat(jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single()).isZero();
    }

    @Test
    void 남의_pet에는_루틴을_쓸_수_없다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String owner = newUserToken();
        String pet = createPet(owner);
        String other = newUserToken();
        call(jsonPut(url(pet), body(5, 5)), other, 404);
        assertThat(jdbc.sql("select count(*) from daily_logs where pet_id = :p").param("p", UUID.fromString(pet))
                .query(Integer.class).single()).isZero();
        // 주인이 저장한 값은 남이 바꾸지 못한다
        call(jsonPut(url(pet), body(1, 1)), owner, 200);
        call(jsonPut(url(pet), body(9, 9)), other, 404);
        assertThat(today(owner, pet).get("walkCount").asInt()).isEqualTo(1);
    }

    @Test
    void DB_CHECK는_범위_밖_값을_거부한다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(1, 1)), t, 200);
        for (int bad : new int[] {21, -1}) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.sql("update daily_logs set walk_count = :v where pet_id = :p")
                            .param("v", bad).param("p", UUID.fromString(pet)).update());
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.sql("update daily_logs set poop_count = :v where pet_id = :p")
                            .param("v", bad).param("p", UUID.fromString(pet)).update());
        }
    }

    @Test
    void 빈_본문은_기존_8필드를_지우되_루틴은_유지한다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), body(4, 6)), t, 200);
        JsonNode res = rawPut(t, pet, "{}", 200);
        assertThat(res.get("foodLevel").isNull()).isTrue();
        assertThat(res.get("memo").isNull()).isTrue();
        assertThat(res.get("walkCount").asInt()).isEqualTo(4);
        assertThat(res.get("poopCount").asInt()).isEqualTo(6);
    }

    @Test
    void 루틴을_쓰지_않은_행의_history_dailyLog에도_필드가_null로_있다() throws Exception {
        setSeoulTime(2026, 10, 12, 12, 0);
        String t = newUserToken();
        String pet = createPet(t);
        call(jsonPut(url(pet), legacyBody(2)), t, 200);
        JsonNode log = call(get("/api/pets/" + pet + "/daily-logs?from=" + DATE + "&to=" + DATE), t, 200)
                .get("days").get(0).get("dailyLog");
        assertThat(log.has("walkCount")).isTrue();
        assertThat(log.get("walkCount").isNull()).isTrue();
        assertThat(log.get("poopCount").isNull()).isTrue();
    }
}
