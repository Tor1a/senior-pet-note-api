package com.oraegyeot.seniorpet.today;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** "오늘" 화면·투약 일정·투약 체크·일일 기록 통합 테스트 (docs/api-today.md 2~5절). */
class TodayApiTest extends ApiTestSupport {

    private Map<String, Object> dailyBody() {
        Map<String, Object> b = new HashMap<>();
        b.put("foodLevel", 2);
        b.put("waterLevel", null);
        b.put("waterMl", 350);
        b.put("weightKg", null);
        b.put("symptoms", List.of());
        b.put("symptomsNone", true);
        b.put("symptomOther", null);
        b.put("memo", "");
        return b;
    }

    private JsonNode today(String token, String petId) throws Exception {
        return call(get("/api/pets/" + petId + "/today"), token, 200);
    }

    @Test
    void 새벽4시_전에는_today_medlog_dailylog가_모두_전날을_쓴다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("20:00", "08:00"));

        setSeoulTime(2026, 10, 6, 3, 59);
        assertThat(today(t, pet).get("recordDate").asText()).isEqualTo("2026-10-05");
        JsonNode log = call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "20:00")), t, 201);
        assertThat(log.get("recordDate").asText()).isEqualTo("2026-10-05");
        assertThat(log.get("takenAt").asText()).isEqualTo("2026-10-05T18:59:00Z");
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-05", dailyBody()), t, 200);
        JsonNode bad = call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-06", dailyBody()), t, 400);
        assertThat(bad.get("code").asText()).isEqualTo("INVALID_RECORD_DATE");

        // 전날 화면: 20:00 회차 체크됨, 일일 기록 있음
        JsonNode day = today(t, pet);
        assertThat(day.get("doses").get(1).get("scheduledTime").asText()).isEqualTo("20:00");
        assertThat(day.get("doses").get(1).get("taken").asBoolean()).isTrue();
        assertThat(day.get("dailyLog").get("recordDate").asText()).isEqualTo("2026-10-05");
    }

    @Test
    void 새벽4시_정각부터는_today_medlog_dailylog가_모두_당일을_쓴다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("08:00"));

        setSeoulTime(2026, 10, 6, 4, 0);
        JsonNode day = today(t, pet);
        assertThat(day.get("recordDate").asText()).isEqualTo("2026-10-06");
        assertThat(day.get("cutoffNotice").asText()).isEqualTo("새벽 4시 전 투약은 전날 기록으로 저장돼요");
        JsonNode log = call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), t, 201);
        assertThat(log.get("recordDate").asText()).isEqualTo("2026-10-06");
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-06", dailyBody()), t, 200);
        JsonNode bad = call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-05", dailyBody()), t, 400);
        assertThat(bad.get("code").asText()).isEqualTo("INVALID_RECORD_DATE");
        call(jsonPut("/api/pets/" + pet + "/daily-logs/not-a-date", dailyBody()), t, 400);
    }

    @Test
    void today_전체_흐름과_doses_정렬() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String medA = createMedication(t, pet, List.of("20:00", "08:00"));
        String medB = call(jsonPost("/api/pets/" + pet + "/medications",
                Map.of("name", "심장약", "times", List.of("12:30"))), t, 201).get("id").asText();
        setSeoulTime(2026, 10, 6, 9, 0);

        JsonNode day = today(t, pet);
        assertThat(day.get("dailyLog").isNull()).isTrue();
        assertThat(day.get("lastWeight").isNull()).isTrue();
        assertThat(day.get("suggestions").get("foodLevel").isNull()).isTrue();
        JsonNode doses = day.get("doses");
        assertThat(doses).hasSize(3);
        assertThat(doses.get(0).get("scheduledTime").asText()).isEqualTo("08:00");
        assertThat(doses.get(1).get("scheduledTime").asText()).isEqualTo("12:30");
        assertThat(doses.get(1).get("medicationId").asText()).isEqualTo(medB);
        assertThat(doses.get(1).get("doseText").isNull()).isTrue();
        assertThat(doses.get(2).get("scheduledTime").asText()).isEqualTo("20:00");
        assertThat(doses.get(0).get("taken").asBoolean()).isFalse();
        assertThat(doses.get(0).get("medLogId").isNull()).isTrue();

        // 체크 → taken=true, 취소 → taken=false
        String logId = call(jsonPost("/api/med-logs", Map.of("medicationId", medA, "scheduledTime", "08:00")), t, 201)
                .get("id").asText();
        JsonNode first = today(t, pet).get("doses").get(0);
        assertThat(first.get("taken").asBoolean()).isTrue();
        assertThat(first.get("medLogId").asText()).isEqualTo(logId);
        assertThat(first.get("takenAt").asText()).isEqualTo("2026-10-06T00:00:00Z");
        call(delete("/api/med-logs/" + logId), t, 204);
        assertThat(today(t, pet).get("doses").get(0).get("taken").asBoolean()).isFalse();

        // 약 삭제(비활성화) → 목록과 today 에서 빠진다
        call(delete("/api/medications/" + medB), t, 204);
        assertThat(call(get("/api/pets/" + pet + "/medications"), t, 200)).hasSize(1);
        assertThat(today(t, pet).get("doses")).hasSize(2);
    }

    @Test
    void 제안값과_직전_체중은_서버가_계산한다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        Map<String, Object> b = dailyBody();

        setSeoulTime(2026, 10, 3, 10, 0);
        b.put("foodLevel", 1);
        b.put("weightKg", 4.4);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-03", b), t, 200);
        setSeoulTime(2026, 10, 4, 10, 0);
        b.put("foodLevel", 2);
        b.put("waterMl", null);
        b.put("weightKg", 4.3);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-04", b), t, 200);
        setSeoulTime(2026, 10, 5, 10, 0);
        b.put("foodLevel", null);
        b.put("weightKg", null);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-05", b), t, 200);

        setSeoulTime(2026, 10, 6, 10, 0);
        JsonNode day = today(t, pet);
        JsonNode s = day.get("suggestions");
        assertThat(s.get("foodLevel").asInt()).isEqualTo(2);   // (1+2)/2 = 1.5 → 2
        assertThat(s.get("waterLevel").isNull()).isTrue();
        assertThat(s.get("waterMl").asInt()).isEqualTo(350);   // 값이 있는 날은 10-03(350) 하나
        assertThat(s.get("weightKg").asDouble()).isEqualTo(4.35);
        assertThat(day.get("lastWeight").get("weightKg").asDouble()).isEqualTo(4.3);
        assertThat(day.get("lastWeight").get("recordDate").asText()).isEqualTo("2026-10-04");
    }

    @Test
    void 같은_회차_중복_체크는_409() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("08:00"));
        Map<String, Object> body = Map.of("medicationId", med, "scheduledTime", "08:00");
        call(jsonPost("/api/med-logs", body), t, 201);
        JsonNode err = call(jsonPost("/api/med-logs", body), t, 409);
        assertThat(err.get("code").asText()).isEqualTo("ALREADY_CHECKED");
    }

    @Test
    void 약_일정에_없는_시각은_400() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("08:00", "20:00"));
        JsonNode err = call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "09:00")), t, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "8시")), t, 400);
    }

    @Test
    void 투약_일정_검증_시각_개수와_중복() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String url = "/api/pets/" + pet + "/medications";
        call(jsonPost(url, Map.of("name", "약", "times", List.of())), t, 400);
        call(jsonPost(url, Map.of("name", "약", "times", List.of("08:00", "09:00", "10:00", "11:00"))), t, 400);
        call(jsonPost(url, Map.of("name", "약", "times", List.of("08:00", "08:00"))), t, 400);
        call(jsonPost(url, Map.of("name", "약", "times", List.of("24:00"))), t, 400);

        // 수정: times 가 정렬되어 저장된다
        String med = createMedication(t, pet, List.of("08:00"));
        JsonNode updated = call(jsonPut("/api/medications/" + med,
                Map.of("name", "아조딜", "doseText", "반 캡슐", "times", List.of("21:00", "07:30"))), t, 200);
        assertThat(updated.get("times").get(0).asText()).isEqualTo("07:30");
        assertThat(updated.get("times").get(1).asText()).isEqualTo("21:00");
        assertThat(updated.get("doseText").asText()).isEqualTo("반 캡슐");
        assertThat(updated.get("active").asBoolean()).isTrue();
        assertThat(updated.get("petId").asText()).isEqualTo(pet);
    }

    @Test
    void 일일_기록은_upsert로_하루_1건() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 6, 12, 0);
        String url = "/api/pets/" + pet + "/daily-logs/2026-10-06";

        JsonNode first = call(jsonPut(url, dailyBody()), t, 200);
        Map<String, Object> b = dailyBody();
        b.put("symptomsNone", false);
        b.put("symptoms", List.of("vomit", "other"));
        b.put("symptomOther", "재채기");
        b.put("weightKg", 4.35);
        b.put("memo", "산책 짧게");
        JsonNode second = call(jsonPut(url, b), t, 200);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("symptoms")).hasSize(2);
        assertThat(second.get("symptomOther").asText()).isEqualTo("재채기");
        assertThat(second.get("weightKg").asDouble()).isEqualTo(4.35);
        assertThat(second.get("foodLevel").asInt()).isEqualTo(2);
        assertThat(second.get("waterLevel").isNull()).isTrue();
        JsonNode daily = today(t, pet).get("dailyLog");
        assertThat(daily.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(daily.get("memo").asText()).isEqualTo("산책 짧게");
    }

    @Test
    void 증상_규칙_검증() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String url = "/api/pets/" + pet + "/daily-logs/" + today(t, pet).get("recordDate").asText();

        // symptomsNone + 증상 동시 → 400
        Map<String, Object> b = dailyBody();
        b.put("symptoms", List.of("cough"));
        assertThat(call(jsonPut(url, b), t, 400).get("code").asText()).isEqualTo("VALIDATION_ERROR");

        // other 없이 symptomOther → 400
        b = dailyBody();
        b.put("symptomsNone", false);
        b.put("symptoms", List.of("cough"));
        b.put("symptomOther", "재채기");
        call(jsonPut(url, b), t, 400);

        // symptomOther 31자 → 400
        b.put("symptoms", List.of("other"));
        b.put("symptomOther", "가".repeat(31));
        call(jsonPut(url, b), t, 400);

        // 알 수 없는 증상, 범위 밖 값, 메모 201자 → 400
        b = dailyBody();
        b.put("symptomsNone", false);
        b.put("symptoms", List.of("sneeze"));
        call(jsonPut(url, b), t, 400);
        b = dailyBody();
        b.put("foodLevel", 4);
        call(jsonPut(url, b), t, 400);
        b = dailyBody();
        b.put("weightKg", 200);
        call(jsonPut(url, b), t, 400);
        b = dailyBody();
        b.put("memo", "가".repeat(201));
        call(jsonPut(url, b), t, 400);

        // other + 30자 → 200
        b = dailyBody();
        b.put("symptomsNone", false);
        b.put("symptoms", List.of("other"));
        b.put("symptomOther", "가".repeat(30));
        call(jsonPut(url, b), t, 200);
    }
}
