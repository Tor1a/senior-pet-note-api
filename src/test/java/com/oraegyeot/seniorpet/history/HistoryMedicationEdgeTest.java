package com.oraegyeot.seniorpet.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 지난 기록 투약 집계 경계 (QA 추가). */
class HistoryMedicationEdgeTest extends ApiTestSupport {

    private JsonNode history(String t, String pet, String q) throws Exception {
        return call(get("/api/pets/" + pet + "/daily-logs" + q), t, 200);
    }

    private JsonNode check(String t, String med, String time) throws Exception {
        return call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", time)), t, 201);
    }

    private String med(String t, String pet, String name, List<String> times) throws Exception {
        return call(jsonPost("/api/pets/" + pet + "/medications",
                Map.of("name", name, "times", times)), t, 201).get("id").asText();
    }

    private void assertDay(JsonNode res, String date, int scheduled, int taken) {
        for (JsonNode d : res.get("days")) {
            if (d.get("recordDate").asText().equals(date)) {
                assertThat(d.get("medication").get("scheduledCount").asInt()).as(date + " scheduled").isEqualTo(scheduled);
                assertThat(d.get("medication").get("takenCount").asInt()).as(date + " taken").isEqualTo(taken);
                return;
            }
        }
        throw new AssertionError("날짜 없음 " + date);
    }

    @Test
    void 같은_날_약_여러_개와_시각_3개를_각각_센다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "14:00", "20:00"));
        String b = med(t, pet, "B", List.of("08:00", "21:00"));

        setSeoulTime(2026, 10, 2, 9, 0);
        check(t, a, "08:00");
        check(t, a, "14:00");
        check(t, a, "20:00");
        check(t, b, "08:00");
        setSeoulTime(2026, 10, 3, 9, 0);
        check(t, b, "08:00"); // B 만 체크, A 같은 시각 08:00 은 별개

        JsonNode res = history(t, pet, "?from=2026-10-02&to=2026-10-03");
        assertDay(res, "2026-10-02", 5, 4);
        assertDay(res, "2026-10-03", 5, 1);
    }

    @Test
    void 체크_취소하면_집계에서_빠지고_다시_체크하면_돌아온다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "20:00"));
        setSeoulTime(2026, 10, 2, 9, 0);
        String logId = check(t, a, "08:00").get("id").asText();
        check(t, a, "20:00");
        assertDay(history(t, pet, "?from=2026-10-02&to=2026-10-02"), "2026-10-02", 2, 2);

        call(delete("/api/med-logs/" + logId), t, 204);
        assertDay(history(t, pet, "?from=2026-10-02&to=2026-10-02"), "2026-10-02", 2, 1);

        check(t, a, "08:00");
        assertDay(history(t, pet, "?from=2026-10-02&to=2026-10-02"), "2026-10-02", 2, 2);
    }

    @Test
    void 새벽_3시59분_체크는_전날로_4시_체크는_당일로_집계된다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("23:00", "01:00"));

        setSeoulTime(2026, 10, 6, 3, 59); // 기록 날짜 10-05
        check(t, a, "01:00");
        setSeoulTime(2026, 10, 6, 4, 0); // 기록 날짜 10-06
        check(t, a, "23:00");

        setSeoulTime(2026, 10, 6, 12, 0);
        JsonNode res = history(t, pet, "?from=2026-10-04&to=2026-10-06");
        assertDay(res, "2026-10-04", 2, 0);
        assertDay(res, "2026-10-05", 2, 1);
        assertDay(res, "2026-10-06", 2, 1);
    }

    @Test
    void 약_시각을_바꾸면_옛_시각_체크는_세지_않고_되돌리면_다시_센다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00"));
        setSeoulTime(2026, 10, 2, 9, 0);
        check(t, a, "08:00");
        setSeoulTime(2026, 10, 3, 9, 0);

        call(jsonPut("/api/medications/" + a, Map.of("name", "A", "times", List.of("09:00"))), t, 200);
        JsonNode changed = history(t, pet, "?from=2026-10-02&to=2026-10-02");
        assertDay(changed, "2026-10-02", 1, 0);

        call(jsonPut("/api/medications/" + a, Map.of("name", "A", "times", List.of("08:00"))), t, 200);
        assertDay(history(t, pet, "?from=2026-10-02&to=2026-10-02"), "2026-10-02", 1, 1);
    }

    @Test
    void 구간_90일_응답은_정렬되고_크기가_작다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 7, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "14:00", "20:00"));
        for (int i = 0; i < 90; i++) {
            LocalDate d = LocalDate.of(2026, 7, 11).plusDays(i);
            setSeoulTime(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 12, 0); // 과거 수정은 막혀 있어 당일로 맞춘다
            call(jsonPut("/api/pets/" + pet + "/daily-logs/" + d, Map.of(
                    "foodLevel", 2, "waterMl", 300, "weightKg", 4.0 + i / 100.0,
                    "symptoms", List.of("vomit"), "memo", "메모".repeat(20))), t, 200);
        }
        setSeoulTime(2026, 10, 8, 12, 0);
        String body = mvc.perform(get("/api/pets/" + pet + "/daily-logs?from=2026-07-11&to=2026-10-08")
                        .header("Authorization", bearer(t)))
                .andReturn().getResponse().getContentAsString();
        JsonNode res = objectMapper.readTree(body);
        assertThat(res.get("days")).hasSize(90);
        LocalDate prev = null;
        for (JsonNode d : res.get("days")) {
            LocalDate cur = LocalDate.parse(d.get("recordDate").asText());
            if (prev != null) {
                assertThat(cur).isEqualTo(prev.plusDays(1));
            }
            prev = cur;
            assertThat(d.get("medication").get("scheduledCount").asInt()).isEqualTo(3);
        }
        assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(100_000);
    }
}
