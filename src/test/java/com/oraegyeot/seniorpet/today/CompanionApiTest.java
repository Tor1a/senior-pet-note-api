package com.oraegyeot.seniorpet.today;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** "오늘" 화면 companion(함께한 날·첫 기록일 기준 기록 N일째) 통합 테스트 (docs/api-companion.md). */
class CompanionApiTest extends ApiTestSupport {

    /** 반려동물을 만든 뒤 등록 시각·입양일을 DB 에 직접 맞춘다(API 는 미래 입양일을 막고 등록 시각은 실제 시계라서). */
    private String pet(String t, String profileType, String adoptedOn, Instant createdAt) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "초코");
        b.put("species", "dog");
        if (profileType != null) {
            b.put("profileType", profileType);
        }
        String id = call(jsonPost("/api/pets", b), t, 201).get("id").asText();
        jdbc.sql("update pets set adopted_on = :a, created_at = :c where id = :id")
                .param("a", adoptedOn == null ? null : LocalDate.parse(adoptedOn))
                .param("c", java.sql.Timestamp.from(createdAt))
                .param("id", UUID.fromString(id)).update();
        return id;
    }

    private JsonNode today(String t, String pet) throws Exception {
        return call(get("/api/pets/" + pet + "/today"), t, 200);
    }

    /** 해당 서울 시각으로 시계를 옮기고 그 기록 날짜의 일일 기록을 저장한다 */
    private void log(String t, String pet, int y, int mo, int d, int h, int mi) throws Exception {
        setSeoulTime(y, mo, d, h, mi);
        String date = call(get("/api/pets/" + pet + "/today"), t, 200).get("recordDate").asText();
        call(jsonPut("/api/pets/" + pet + "/daily-logs/" + date,
                Map.of("foodLevel", 3, "symptoms", List.of(), "symptomsNone", true)), t, 200);
    }

    private static Instant seoul(int y, int mo, int d, int h, int mi) {
        return java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(SEOUL).toInstant();
    }

    @Test
    void first_time_입양일_있음_첫_기록일_기준() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-10-05", seoul(2026, 10, 1, 9, 0)); // 등록일은 기준이 아니다
        log(t, pet, 2026, 10, 10, 9, 0);
        log(t, pet, 2026, 10, 12, 9, 0);
        setSeoulTime(2026, 10, 14, 12, 0);
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(10);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(5); // 10-10 이 1일째
    }

    @Test
    void 기록이_없으면_recordingDays_null_입양일_줄은_그대로() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-10-05", seoul(2026, 10, 1, 9, 0));
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(10);
        assertThat(c.has("recordingDays")).isTrue();
        assertThat(c.get("recordingDays").isNull()).isTrue();
    }

    @Test
    void first_time_입양일_없음이면_togetherDays_null() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 14, 9, 0));
        log(t, pet, 2026, 10, 14, 10, 0);
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.has("togetherDays")).isTrue();
        assertThat(c.get("togetherDays").isNull()).isTrue();
        assertThat(c.get("recordingDays").asInt()).isEqualTo(1);
    }

    @Test
    void senior_와_유형없음은_companion_null이고_기존_필드는_그대로다() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        for (String type : new String[] {"senior", null}) {
            String t = newUserToken(); // 사용자당 반려동물 1마리
            String pet = pet(t, type, "2026-10-05", seoul(2026, 10, 10, 9, 0));
            JsonNode r = today(t, pet);
            assertThat(r.has("companion")).isTrue();
            assertThat(r.get("companion").isNull()).isTrue();
            assertThat(r.get("recordDate").asText()).isEqualTo("2026-10-14");
            assertThat(r.has("cutoffNotice") && r.has("doses") && r.has("dailyLog")
                    && r.has("suggestions") && r.has("lastWeight")).isTrue();
        }
    }

    @Test
    void 새벽4시_경계에서_숫자가_4시에_바뀐다() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-10-05", seoul(2026, 10, 1, 9, 0));
        log(t, pet, 2026, 10, 10, 9, 0);

        setSeoulTime(2026, 10, 14, 3, 59); // 기록 날짜 10-13
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(9);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(4);

        setSeoulTime(2026, 10, 14, 4, 0); // 기록 날짜 10-14
        c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(10);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(5);
    }

    @Test
    void 입양일이_달력_오늘이어도_기록_날짜보다_미래면_null() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-10-14", seoul(2026, 10, 1, 9, 0));

        setSeoulTime(2026, 10, 14, 3, 59); // 기록 날짜 10-13 < 입양일
        assertThat(today(t, pet).get("companion").get("togetherDays").isNull()).isTrue();

        setSeoulTime(2026, 10, 14, 4, 0); // 입양일 당일 = 1일째
        assertThat(today(t, pet).get("companion").get("togetherDays").asInt()).isEqualTo(1);
    }

    @Test
    void 첫_기록_당일은_1일째_다음날은_2일째() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 1, 10, 0));
        log(t, pet, 2026, 10, 14, 10, 0);

        setSeoulTime(2026, 10, 14, 10, 1);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
        setSeoulTime(2026, 10, 15, 3, 59); // 아직 기록 날짜 10-14
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
        setSeoulTime(2026, 10, 15, 4, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(2);
    }

    @Test
    void 새벽_4시_전에_남긴_첫_기록은_전날_기록_날짜로_센다() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 1, 9, 0));
        log(t, pet, 2026, 10, 14, 1, 0); // 기록 날짜 10-13
        setSeoulTime(2026, 10, 14, 12, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(2);
    }

    @Test
    void 기록을_삭제하면_다시_null_더_이른_기록이_남으면_그_날짜_기준() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 1, 9, 0));
        log(t, pet, 2026, 10, 12, 9, 0);
        log(t, pet, 2026, 10, 14, 9, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(3);

        // 오늘(10-14) 기록 삭제: 10-12 가 남아 있으므로 그대로 3일째
        call(delete("/api/pets/" + pet + "/daily-logs/2026-10-14"), t, 204);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(3);

        // 마지막 기록(10-12)을 지우면 null: 날짜를 10-12 로 되돌려 삭제
        setSeoulTime(2026, 10, 12, 9, 30);
        call(delete("/api/pets/" + pet + "/daily-logs/2026-10-12"), t, 204);
        setSeoulTime(2026, 10, 14, 9, 30);
        assertThat(today(t, pet).get("companion").get("recordingDays").isNull()).isTrue();
    }

    @Test
    void 첫_기록을_지웠다_다시_저장하면_그날이_1일째() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 1, 9, 0));
        log(t, pet, 2026, 10, 14, 9, 0);
        call(delete("/api/pets/" + pet + "/daily-logs/2026-10-14"), t, 204);
        assertThat(today(t, pet).get("companion").get("recordingDays").isNull()).isTrue();
        log(t, pet, 2026, 10, 14, 9, 5);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
    }

    @Test
    void 다른_반려동물의_기록은_세지_않는다() throws Exception {
        String t = newUserToken();
        String other = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 1, 9, 0));
        String otherPet = pet(other, "first_time", null, seoul(2026, 10, 1, 9, 0));
        log(other, otherPet, 2026, 10, 10, 9, 0);
        setSeoulTime(2026, 10, 14, 12, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").isNull()).isTrue();
    }

    @Test
    void 연말_연초_경계() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-12-31", seoul(2026, 12, 30, 9, 0));
        log(t, pet, 2026, 12, 30, 9, 0);
        setSeoulTime(2026, 12, 31, 12, 0);
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(1);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(2);
        setSeoulTime(2027, 1, 1, 12, 0);
        c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(2);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(3);
        setSeoulTime(2027, 1, 1, 3, 59); // 기록 날짜 12-31
        assertThat(today(t, pet).get("companion").get("togetherDays").asInt()).isEqualTo(1);
    }

    @Test
    void 윤년_경계() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2028-02-28", seoul(2028, 2, 28, 9, 0));
        log(t, pet, 2028, 2, 28, 9, 0);
        setSeoulTime(2028, 3, 1, 12, 0); // 2/28 -> 2/29 -> 3/1
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(3);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(3);
    }

    @Test
    void 남의_pet은_404() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        String owner = newUserToken();
        String pet = pet(owner, "first_time", null, seoul(2026, 10, 10, 9, 0));
        call(get("/api/pets/" + pet + "/today"), newUserToken(), 404);
    }
}
