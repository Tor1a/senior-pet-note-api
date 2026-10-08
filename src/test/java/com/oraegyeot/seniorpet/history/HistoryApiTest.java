package com.oraegyeot.seniorpet.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 지난 기록 조회 API 통합 테스트 (docs/api-history.md). */
class HistoryApiTest extends ApiTestSupport {

    private JsonNode history(String token, String petId, String query, int expected) throws Exception {
        return call(get("/api/pets/" + petId + "/daily-logs" + query), token, expected);
    }

    private void assertInvalid(String token, String petId, String query) throws Exception {
        JsonNode err = history(token, petId, query, 400);
        assertThat(err.get("code").asText()).isEqualTo("INVALID_DATE_RANGE");
        assertThat(err.get("message").asText()).isNotBlank();
    }

    private JsonNode day(JsonNode res, String date) {
        for (JsonNode d : res.get("days")) {
            if (d.get("recordDate").asText().equals(date)) {
                return d;
            }
        }
        throw new AssertionError("날짜 없음: " + date);
    }

    private void check(String token, String med, String time) throws Exception {
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", time)), token, 201);
    }

    @Test
    void 파라미터_없으면_현재_기록날짜까지_30일을_오름차순으로_채운다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 8, 12, 0);

        JsonNode res = history(t, pet, "", 200);
        assertThat(res.get("petId").asText()).isEqualTo(pet);
        assertThat(res.get("from").asText()).isEqualTo("2026-09-09");
        assertThat(res.get("to").asText()).isEqualTo("2026-10-08");
        assertThat(res.get("recordDate").asText()).isEqualTo("2026-10-08");
        assertThat(res.get("medicationBasis").asText()).isEqualTo("current");
        assertThat(res.get("days")).hasSize(30);
        LocalDate expected = LocalDate.of(2026, 9, 9);
        for (JsonNode d : res.get("days")) {
            assertThat(d.get("recordDate").asText()).isEqualTo(expected.toString());
            assertThat(d.get("dailyLog").isNull()).isTrue();
            assertThat(d.get("medication").get("scheduledCount").asInt()).isZero();
            assertThat(d.get("medication").get("takenCount").asInt()).isZero();
            expected = expected.plusDays(1);
        }
    }

    @Test
    void 새벽4시_전에는_전날이_to_4시부터는_당일이_to() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);

        setSeoulTime(2026, 10, 8, 3, 59);
        JsonNode before = history(t, pet, "", 200);
        assertThat(before.get("to").asText()).isEqualTo("2026-10-07");
        assertThat(before.get("recordDate").asText()).isEqualTo("2026-10-07");
        assertThat(before.get("from").asText()).isEqualTo("2026-09-08");
        // 현재 기록 날짜(10-07) 이후는 미래
        assertInvalid(t, pet, "?to=2026-10-08");
        history(t, pet, "?to=2026-10-07", 200);

        setSeoulTime(2026, 10, 8, 4, 0);
        JsonNode after = history(t, pet, "", 200);
        assertThat(after.get("to").asText()).isEqualTo("2026-10-08");
        assertThat(after.get("recordDate").asText()).isEqualTo("2026-10-08");
        history(t, pet, "?to=2026-10-08", 200);
    }

    @Test
    void 기간_경계_1일_30일_90일은_200_91일은_400() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 8, 12, 0);

        JsonNode one = history(t, pet, "?from=2026-10-01&to=2026-10-01", 200);
        assertThat(one.get("days")).hasSize(1);
        assertThat(one.get("days").get(0).get("recordDate").asText()).isEqualTo("2026-10-01");
        assertThat(history(t, pet, "?from=2026-09-09&to=2026-10-08", 200).get("days")).hasSize(30);
        assertThat(history(t, pet, "?from=2026-07-11&to=2026-10-08", 200).get("days")).hasSize(90);
        assertInvalid(t, pet, "?from=2026-07-10&to=2026-10-08");
        assertInvalid(t, pet, "?from=2020-01-01&to=2026-10-08");
    }

    @Test
    void from만_또는_to만_줘도_동작한다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 8, 12, 0);

        JsonNode fromOnly = history(t, pet, "?from=2026-10-02", 200);
        assertThat(fromOnly.get("to").asText()).isEqualTo("2026-10-08");
        assertThat(fromOnly.get("days")).hasSize(7);
        JsonNode toOnly = history(t, pet, "?to=2026-10-03", 200);
        assertThat(toOnly.get("from").asText()).isEqualTo("2026-09-04");
        assertThat(toOnly.get("days")).hasSize(30);
    }

    @Test
    void 잘못된_파라미터는_400_INVALID_DATE_RANGE() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 8, 12, 0);

        assertInvalid(t, pet, "?from=2026-10-05&to=2026-10-04"); // from > to
        assertInvalid(t, pet, "?from=abc");
        assertInvalid(t, pet, "?to=20261008");
        assertInvalid(t, pet, "?from=2026-02-30&to=2026-10-08"); // 없는 날짜
        assertInvalid(t, pet, "?from=2026-10-1&to=2026-10-08");
        assertInvalid(t, pet, "?from=&to=2026-10-08");
        assertInvalid(t, pet, "?to=2026-10-09"); // 미래
        assertInvalid(t, pet, "?from=2026-10-09&to=2026-10-09");
    }

    @Test
    void 기록_없는_날은_null_있는_날은_PUT한_값_그대로() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);

        setSeoulTime(2026, 10, 5, 12, 0);
        Map<String, Object> body = new HashMap<>();
        body.put("foodLevel", 2);
        body.put("waterLevel", null);
        body.put("waterMl", 350);
        body.put("weightKg", 4.4);
        body.put("symptoms", List.of("vomit"));
        body.put("symptomsNone", false);
        body.put("symptomOther", null);
        body.put("memo", "조금 처졌어요");
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-05", body), t, 200);
        Map<String, Object> none = new HashMap<>();
        none.put("symptoms", List.of());
        none.put("symptomsNone", true);
        setSeoulTime(2026, 10, 7, 12, 0);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-07", none), t, 200);

        setSeoulTime(2026, 10, 8, 12, 0);
        JsonNode res = history(t, pet, "?from=2026-10-04&to=2026-10-08", 200);
        assertThat(res.get("days")).hasSize(5);
        assertThat(day(res, "2026-10-04").get("dailyLog").isNull()).isTrue();
        assertThat(day(res, "2026-10-06").get("dailyLog").isNull()).isTrue();
        assertThat(day(res, "2026-10-08").get("dailyLog").isNull()).isTrue();

        JsonNode log = day(res, "2026-10-05").get("dailyLog");
        assertThat(log.get("recordDate").asText()).isEqualTo("2026-10-05");
        assertThat(log.get("petId").asText()).isEqualTo(pet);
        assertThat(log.get("foodLevel").asInt()).isEqualTo(2);
        assertThat(log.get("waterLevel").isNull()).isTrue();
        assertThat(log.get("waterMl").asInt()).isEqualTo(350);
        assertThat(log.get("weightKg").asDouble()).isEqualTo(4.4);
        assertThat(log.get("symptoms").get(0).asText()).isEqualTo("vomit");
        assertThat(log.get("symptomsNone").asBoolean()).isFalse();
        assertThat(log.get("memo").asText()).isEqualTo("조금 처졌어요");
        assertThat(log.get("id").asText()).isNotBlank();
        assertThat(log.get("updatedAt").asText()).isNotBlank();

        JsonNode noneLog = day(res, "2026-10-07").get("dailyLog");
        assertThat(noneLog.get("symptomsNone").asBoolean()).isTrue();
        assertThat(noneLog.get("foodLevel").isNull()).isTrue();
        assertThat(noneLog.get("weightKg").isNull()).isTrue();
    }

    @Test
    void 투약_집계는_활성약_시각수와_체크수() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);

        setSeoulTime(2026, 10, 1, 12, 0); // 약 등록일 = 10-01
        String medA = createMedication(t, pet, List.of("08:00", "20:00"));
        String medB = call(jsonPost("/api/pets/" + pet + "/medications",
                Map.of("name", "심장약", "times", List.of("12:30"))), t, 201).get("id").asText();

        setSeoulTime(2026, 10, 5, 9, 0);
        check(t, medA, "08:00");
        check(t, medA, "20:00");
        setSeoulTime(2026, 10, 6, 13, 0);
        check(t, medB, "12:30");

        setSeoulTime(2026, 10, 8, 12, 0);
        JsonNode res = history(t, pet, "?from=2026-09-29&to=2026-10-08", 200);
        assertThat(res.get("days")).hasSize(10);
        assertCount(day(res, "2026-09-29"), 0, 0); // 등록 전
        assertCount(day(res, "2026-09-30"), 0, 0);
        assertCount(day(res, "2026-10-01"), 3, 0);
        assertCount(day(res, "2026-10-05"), 3, 2);
        assertCount(day(res, "2026-10-06"), 3, 1);
        assertCount(day(res, "2026-10-08"), 3, 0);

        // 약 시각 변경: 옛 시각(08:00) 체크는 세지 않는다 (taken <= scheduled)
        call(jsonPut("/api/medications/" + medA, Map.of("name", "아조딜", "times", List.of("09:00", "20:00"))), t, 200);
        assertCount(day(history(t, pet, "?from=2026-10-05&to=2026-10-05", 200), "2026-10-05"), 3, 1);

        // 삭제(비활성)한 약은 과거에도 포함하지 않는다
        call(delete("/api/medications/" + medB), t, 204);
        JsonNode afterDelete = history(t, pet, "?from=2026-10-05&to=2026-10-06", 200);
        assertCount(day(afterDelete, "2026-10-05"), 2, 1);
        assertCount(day(afterDelete, "2026-10-06"), 2, 0);
    }

    @Test
    void 약_등록이_새벽_4시_전이면_전날부터_예정에_센다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 3, 2, 0); // 기록 날짜 10-02
        createMedication(t, pet, List.of("08:00"));

        setSeoulTime(2026, 10, 4, 12, 0);
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-04", 200);
        assertCount(day(res, "2026-10-01"), 0, 0);
        assertCount(day(res, "2026-10-02"), 1, 0);
        assertCount(day(res, "2026-10-03"), 1, 0);
    }

    @Test
    void 약이_없으면_0_0() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 8, 12, 0);
        JsonNode res = history(t, pet, "?from=2026-10-07&to=2026-10-08", 200);
        assertCount(res.get("days").get(0), 0, 0);
        assertCount(res.get("days").get(1), 0, 0);
    }

    @Test
    void 인증_없으면_401() throws Exception {
        mvc.perform(get("/api/pets/" + java.util.UUID.randomUUID() + "/daily-logs"))
                .andExpect(status().isUnauthorized());
    }

    private void assertCount(JsonNode day, int scheduled, int taken) {
        assertThat(day.get("medication").get("scheduledCount").asInt()).as("scheduled").isEqualTo(scheduled);
        assertThat(day.get("medication").get("takenCount").asInt()).as("taken").isEqualTo(taken);
    }
}
