package com.oraegyeot.seniorpet.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** 약 일정 변경 이력(V7): 시각 변경·횟수 감소·삭제 뒤에도 과거 날짜의 예정/체크 횟수가 당시 일정 기준으로 유지된다. 시계는 모두 고정한다. */
class MedicationScheduleHistoryTest extends ApiTestSupport {

    private record Row(LocalDate from, LocalDate to, String times) {
    }

    private JsonNode history(String t, String pet, String q) throws Exception {
        return call(get("/api/pets/" + pet + "/daily-logs" + q), t, 200);
    }

    private void check(String t, String med, String time) throws Exception {
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", time)), t, 201);
    }

    private String med(String t, String pet, String name, List<String> times) throws Exception {
        return call(jsonPost("/api/pets/" + pet + "/medications", Map.of("name", name, "times", times)), t, 201)
                .get("id").asText();
    }

    private JsonNode put(String t, String med, String name, List<String> times, int status) throws Exception {
        return call(jsonPut("/api/medications/" + med, Map.of("name", name, "times", times)), t, status);
    }

    private List<Row> rows(String med) {
        return jdbc.sql("select valid_from, valid_to, array_to_string(times, ',') as t "
                        + "from medication_schedule_versions where medication_id = :m order by valid_from")
                .param("m", UUID.fromString(med))
                .query((rs, n) -> new Row(rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_to", LocalDate.class), rs.getString("t")))
                .list();
    }

    private static LocalDate d(int m, int day) {
        return LocalDate.of(2026, m, day);
    }

    private void assertDay(JsonNode res, String date, int scheduled, int taken) {
        for (JsonNode day : res.get("days")) {
            if (day.get("recordDate").asText().equals(date)) {
                assertThat(day.get("medication").get("scheduledCount").asInt()).as(date + " scheduled").isEqualTo(scheduled);
                assertThat(day.get("medication").get("takenCount").asInt()).as(date + " taken").isEqualTo(taken);
                return;
            }
        }
        throw new AssertionError("날짜 없음 " + date);
    }

    @Test
    void 하루_3회를_2회로_줄여도_과거_날짜는_3회로_센다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "14:00", "20:00"));
        setSeoulTime(2026, 10, 2, 9, 0);
        check(t, a, "08:00");
        check(t, a, "14:00");
        setSeoulTime(2026, 10, 10, 9, 0);
        put(t, a, "A", List.of("08:00", "20:00"), 200);
        check(t, a, "08:00");

        setSeoulTime(2026, 10, 11, 9, 0);
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-11");
        assertDay(res, "2026-10-01", 3, 0);
        assertDay(res, "2026-10-02", 3, 2); // 변경 전: 3회 중 2회
        assertDay(res, "2026-10-09", 3, 0);
        assertDay(res, "2026-10-10", 2, 1); // 변경한 날부터 새 일정
        assertDay(res, "2026-10-11", 2, 0);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), d(10, 9), "08:00:00,14:00:00,20:00:00"),
                new Row(d(10, 10), null, "08:00:00,20:00:00"));
    }

    @Test
    void 시각을_바꾸면_과거_날짜의_옛_시각_체크가_계속_집계된다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00"));
        setSeoulTime(2026, 10, 2, 9, 0);
        check(t, a, "08:00");
        setSeoulTime(2026, 10, 3, 9, 0);
        put(t, a, "A", List.of("09:00"), 200);
        check(t, a, "09:00");

        JsonNode res = history(t, pet, "?from=2026-10-02&to=2026-10-03");
        assertDay(res, "2026-10-02", 1, 1);
        assertDay(res, "2026-10-03", 1, 1);
        // 원래 시각으로 되돌려도 이력은 새 버전으로 쌓이고 과거 계산은 같다
        setSeoulTime(2026, 10, 4, 9, 0);
        put(t, a, "A", List.of("08:00"), 200);
        res = history(t, pet, "?from=2026-10-02&to=2026-10-04");
        assertDay(res, "2026-10-02", 1, 1);
        assertDay(res, "2026-10-03", 1, 1);
        assertDay(res, "2026-10-04", 1, 0);
        assertThat(rows(a)).hasSize(3);
    }

    @Test
    void 삭제한_약은_삭제_전_날짜에는_세고_삭제_후_날짜에는_세지_않는다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "20:00"));
        setSeoulTime(2026, 10, 2, 9, 0);
        check(t, a, "08:00");
        setSeoulTime(2026, 10, 5, 9, 0);
        call(delete("/api/medications/" + a), t, 204);

        setSeoulTime(2026, 10, 7, 9, 0);
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-07");
        assertDay(res, "2026-10-02", 2, 1);
        assertDay(res, "2026-10-04", 2, 0);
        assertDay(res, "2026-10-05", 0, 0); // 삭제한 날부터는 예정 없음
        assertDay(res, "2026-10-07", 0, 0);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), d(10, 4), "08:00:00,20:00:00"));
    }

    @Test
    void 등록한_날_삭제하면_이력_행이_없고_과거_숫자는_0이다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00"));
        setSeoulTime(2026, 10, 1, 15, 0);
        call(delete("/api/medications/" + a), t, 204);
        assertThat(rows(a)).isEmpty();
        setSeoulTime(2026, 10, 3, 9, 0);
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-03");
        assertDay(res, "2026-10-01", 0, 0);
        assertDay(res, "2026-10-02", 0, 0);
    }

    @Test
    void 같은_날_여러_번_바꿔도_그날_버전_하나만_제자리_갱신된다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "14:00", "20:00"));
        setSeoulTime(2026, 10, 5, 9, 0);
        put(t, a, "A", List.of("08:00", "14:00"), 200);
        setSeoulTime(2026, 10, 5, 12, 0);
        put(t, a, "A", List.of("08:00"), 200);
        setSeoulTime(2026, 10, 5, 18, 0);
        put(t, a, "A", List.of("09:00", "21:00"), 200);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), d(10, 4), "08:00:00,14:00:00,20:00:00"),
                new Row(d(10, 5), null, "09:00:00,21:00:00"));
        JsonNode res = history(t, pet, "?from=2026-10-04&to=2026-10-05");
        assertDay(res, "2026-10-04", 3, 0);
        assertDay(res, "2026-10-05", 2, 0);
    }

    @Test
    void 이름과_용량만_바꾸면_이력은_그대로다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("20:00", "08:00"));
        setSeoulTime(2026, 10, 5, 9, 0);
        put(t, a, "새 이름", List.of("08:00", "20:00"), 200); // 순서만 다른 같은 집합
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), null, "08:00:00,20:00:00"));
    }

    @Test
    void 새벽_3시59분_변경은_전날부터_4시_변경은_당일부터_적용된다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "20:00"));
        String b = med(t, pet, "B", List.of("08:00", "20:00"));
        setSeoulTime(2026, 10, 6, 3, 59); // 기록 날짜 10-05
        put(t, a, "A", List.of("08:00"), 200);
        setSeoulTime(2026, 10, 6, 4, 0); // 기록 날짜 10-06
        put(t, b, "B", List.of("08:00"), 200);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), d(10, 4), "08:00:00,20:00:00"),
                new Row(d(10, 5), null, "08:00:00"));
        assertThat(rows(b)).containsExactly(new Row(d(10, 1), d(10, 5), "08:00:00,20:00:00"),
                new Row(d(10, 6), null, "08:00:00"));
        // 새벽 3시59분 삭제는 전날 기준
        setSeoulTime(2026, 10, 8, 3, 59); // 기록 날짜 10-07
        call(delete("/api/medications/" + a), t, 204);
        assertThat(rows(a).get(1).to()).isEqualTo(d(10, 6));
    }

    @Test
    void 모든_날짜에서_체크_수는_예정_수를_넘지_않는다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00", "14:00", "20:00"));
        String b = med(t, pet, "B", List.of("09:00"));
        for (int day = 2; day <= 8; day++) {
            setSeoulTime(2026, 10, day, 10, 0);
            if (day == 4) {
                put(t, a, "A", List.of("14:00"), 200);
            }
            check(t, a, day >= 4 ? "14:00" : "08:00");
            if (day < 6) {
                check(t, b, "09:00");
            } else if (day == 6) {
                call(delete("/api/medications/" + b), t, 204);
            }
        }
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-08");
        for (JsonNode day : res.get("days")) {
            JsonNode m = day.get("medication");
            assertThat(m.get("takenCount").asInt()).as(day.get("recordDate").asText())
                    .isLessThanOrEqualTo(m.get("scheduledCount").asInt());
        }
        assertDay(res, "2026-10-03", 4, 2);
        assertDay(res, "2026-10-05", 2, 2); // A 14:00 + B 09:00
        assertDay(res, "2026-10-07", 1, 1);
    }

    @Test
    void 응답_키와_medicationBasis는_그대로다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        med(t, pet, "A", List.of("08:00"));
        JsonNode res = history(t, pet, "?from=2026-10-01&to=2026-10-01");
        List<String> keys = new ArrayList<>();
        res.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("petId", "from", "to", "recordDate", "medicationBasis", "days");
        assertThat(res.get("medicationBasis").asText()).isEqualTo("current");
        List<String> dayKeys = new ArrayList<>();
        res.get("days").get(0).fieldNames().forEachRemaining(dayKeys::add);
        assertThat(dayKeys).containsExactlyInAnyOrder("recordDate", "dailyLog", "medication");
        List<String> medKeys = new ArrayList<>();
        res.get("days").get(0).get("medication").fieldNames().forEachRemaining(medKeys::add);
        assertThat(medKeys).containsExactlyInAnyOrder("scheduledCount", "takenCount");
    }

    @Test
    void 남의_약_수정_삭제는_404이고_이력은_그대로이며_이력이_다른_사용자_기록에_섞이지_않는다() throws Exception {
        String owner = newUserToken();
        String other = newUserToken();
        String ownerPet = createPet(owner);
        String otherPet = createPet(other);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(owner, ownerPet, "A", List.of("08:00", "20:00"));
        setSeoulTime(2026, 10, 5, 9, 0);
        put(other, a, "X", List.of("09:00"), 404);
        call(delete("/api/medications/" + a), other, 404);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), null, "08:00:00,20:00:00"));
        // 다른 사용자의 지난 기록에는 내 약이 보이지 않는다
        JsonNode res = history(other, otherPet, "?from=2026-10-01&to=2026-10-05");
        assertDay(res, "2026-10-03", 0, 0);
        // 남의 pet 의 history 는 여전히 404
        call(get("/api/pets/" + ownerPet + "/daily-logs"), other, 404);
    }

    @Test
    void 삭제한_약을_다시_수정_삭제하면_404이고_이력은_그대로다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00"));
        setSeoulTime(2026, 10, 3, 12, 0);
        call(delete("/api/medications/" + a), t, 204);
        put(t, a, "A", List.of("09:00"), 404);
        call(delete("/api/medications/" + a), t, 404);
        assertThat(rows(a)).containsExactly(new Row(d(10, 1), d(10, 2), "08:00:00"));
    }

    @Test
    void 같은_약을_동시에_수정해도_열린_버전은_하나이고_5xx가_없다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        String a = med(t, pet, "A", List.of("08:00"));
        setSeoulTime(2026, 10, 5, 9, 0);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String time = "1" + i + ":00";
                Callable<Integer> c = () -> mvc.perform(jsonPut("/api/medications/" + a,
                                Map.of("name", "A", "times", List.of(time)))
                                .header("Authorization", bearer(t)))
                        .andReturn().getResponse().getStatus();
                fs.add(pool.submit(c));
            }
            for (Future<Integer> f : fs) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(a).stream().filter(r -> r.to() == null)).hasSize(1);
        assertThat(rows(a)).hasSize(2); // 10-01~10-04, 10-05~ (같은 날 변경은 제자리 갱신)
    }

    @Test
    void 약_등록_중_예외가_나면_약과_이력이_함께_롤백된다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 1, 12, 0);
        long before = jdbc.sql("select count(*) from medication_schedule_versions where user_id = :u")
                .param("u", userIdOf(t)).query(Long.class).single();
        // 시각 중복 → 400, 아무것도 남지 않는다
        call(jsonPost("/api/pets/" + pet + "/medications", Map.of("name", "A", "times", List.of("08:00", "08:00"))), t, 400);
        long after = jdbc.sql("select count(*) from medication_schedule_versions where user_id = :u")
                .param("u", userIdOf(t)).query(Long.class).single();
        assertThat(after).isEqualTo(before);
    }
}
