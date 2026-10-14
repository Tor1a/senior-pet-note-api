package com.oraegyeot.seniorpet.today;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** "오늘" 화면 companion(함께한 날·기록 N일째) 통합 테스트 (docs/api-companion.md). */
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

    private static Instant seoul(int y, int mo, int d, int h, int mi) {
        return java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(SEOUL).toInstant();
    }

    @Test
    void first_time_입양일_있음_N일째() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-10-05", seoul(2026, 10, 10, 9, 0));
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(10);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(5);
    }

    @Test
    void first_time_입양일_없음이면_togetherDays_null() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 14, 9, 0));
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
        String pet = pet(t, "first_time", "2026-10-05", seoul(2026, 10, 10, 9, 0));

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
    void 등록_당일은_기록_1일째_다음날은_2일째() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 14, 10, 0));

        setSeoulTime(2026, 10, 14, 10, 1);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
        setSeoulTime(2026, 10, 15, 3, 59); // 아직 기록 날짜 10-14
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
        setSeoulTime(2026, 10, 15, 4, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(2);
    }

    @Test
    void 등록이_새벽_4시_전이면_전날_기록_날짜로_센다() throws Exception {
        String t = newUserToken();
        // 서울 10-14 01:00 등록 -> 기록 날짜 10-13
        String pet = pet(t, "first_time", null, seoul(2026, 10, 14, 1, 0));
        setSeoulTime(2026, 10, 14, 12, 0);
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(2);
    }

    @Test
    void 서울_UTC_날짜가_다른_등록_시각도_서울_기준으로_센다() throws Exception {
        String t = newUserToken();
        String t2 = newUserToken();
        // 2026-10-05T19:00Z = 서울 10-06 04:00 (UTC 날짜는 10-05, 서울 기록 날짜는 10-06)
        String a = pet(t, "first_time", null, Instant.parse("2026-10-05T19:00:00Z"));
        // 2026-10-05T18:59Z = 서울 10-06 03:59 -> 기록 날짜 10-05
        String b = pet(t2, "first_time", null, Instant.parse("2026-10-05T18:59:00Z"));
        setSeoulTime(2026, 10, 6, 12, 0);
        assertThat(today(t, a).get("companion").get("recordingDays").asInt()).isEqualTo(1);
        assertThat(today(t2, b).get("companion").get("recordingDays").asInt()).isEqualTo(2);
    }

    @Test
    void 연말_연초_경계() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", "2026-12-31", seoul(2026, 12, 30, 9, 0));
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
        setSeoulTime(2028, 3, 1, 12, 0); // 2/28 -> 2/29 -> 3/1
        JsonNode c = today(t, pet).get("companion");
        assertThat(c.get("togetherDays").asInt()).isEqualTo(3);
        assertThat(c.get("recordingDays").asInt()).isEqualTo(3);
    }

    @Test
    void 등록_시각이_기록_날짜보다_뒤여도_1일_미만이_되지_않는다() throws Exception {
        String t = newUserToken();
        String pet = pet(t, "first_time", null, seoul(2026, 10, 20, 9, 0));
        setSeoulTime(2026, 10, 14, 12, 0); // 시계가 등록보다 과거(비정상)
        assertThat(today(t, pet).get("companion").get("recordingDays").asInt()).isEqualTo(1);
    }

    @Test
    void 남의_pet은_404() throws Exception {
        setSeoulTime(2026, 10, 14, 12, 0);
        String owner = newUserToken();
        String pet = pet(owner, "first_time", null, seoul(2026, 10, 10, 9, 0));
        call(get("/api/pets/" + pet + "/today"), newUserToken(), 404);
    }
}
