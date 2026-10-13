package com.oraegyeot.seniorpet.pet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 반려동물 유형·생일·입양일 통합 테스트 (docs/api-pet-profile.md). */
class PetProfileApiTest extends ApiTestSupport {

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "초코");
        b.put("species", "dog");
        for (int i = 0; i < kv.length; i += 2) {
            b.put((String) kv[i], kv[i + 1]);
        }
        return b;
    }

    private JsonNode rawPut(String t, String pet, String json, int expected) throws Exception {
        return call(put("/api/pets/" + pet).contentType(MediaType.APPLICATION_JSON).content(json), t, expected);
    }

    private JsonNode create(String t, Map<String, Object> b, int expected) throws Exception {
        return call(jsonPost("/api/pets", b), t, expected);
    }

    private void assertValidation(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    /** 값이 다 들어 있는 pet 을 만든다. */
    private String fullPet(String t) throws Exception {
        JsonNode r = create(t, body("birthYear", 2015, "birthDate", "2015-03-02", "adoptedOn", "2016-01-10",
                "profileType", "senior"), 201);
        return r.get("id").asText();
    }

    @Test
    void 값이_등록_조회_수정으로_왕복된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        JsonNode r = create(t, body("birthYear", 2015, "birthDate", "2015-03-02", "adoptedOn", "2016-01-10",
                "profileType", "first_time"), 201);
        assertThat(r.get("profileType").asText()).isEqualTo("first_time");
        assertThat(r.get("birthDate").asText()).isEqualTo("2015-03-02");
        assertThat(r.get("adoptedOn").asText()).isEqualTo("2016-01-10");
        String id = r.get("id").asText();
        JsonNode got = call(get("/api/pets/" + id), t, 200);
        assertThat(got.get("birthDate").asText()).isEqualTo("2015-03-02");
        JsonNode upd = call(jsonPut("/api/pets/" + id, body("birthYear", 2015, "birthDate", "2015-04-05",
                "adoptedOn", "2016-02-01", "profileType", "senior")), t, 200);
        assertThat(upd.get("profileType").asText()).isEqualTo("senior");
        assertThat(upd.get("birthDate").asText()).isEqualTo("2015-04-05");
        assertThat(upd.get("adoptedOn").asText()).isEqualTo("2016-02-01");
    }

    @Test
    void 새_키가_없으면_응답에_세_필드가_null로_항상_있다() throws Exception {
        String t = newUserToken();
        JsonNode r = create(t, body("birthYear", 2015), 201);
        for (String f : new String[] {"profileType", "birthDate", "adoptedOn"}) {
            assertThat(r.has(f)).as(f).isTrue();
            assertThat(r.get(f).isNull()).as(f).isTrue();
        }
        JsonNode list = call(get("/api/pets"), t, 200);
        assertThat(list.get(0).has("birthDate")).isTrue();
    }

    @Test
    void PUT에서_생략하면_기존_값이_유지된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        // 옛 형식 본문: 새 키 없음, birthYear 는 그대로
        JsonNode r = rawPut(t, id, "{\"name\":\"뭉치\",\"species\":\"dog\",\"birthYear\":2015}", 200);
        assertThat(r.get("name").asText()).isEqualTo("뭉치");
        assertThat(r.get("profileType").asText()).isEqualTo("senior");
        assertThat(r.get("birthDate").asText()).isEqualTo("2015-03-02");
        assertThat(r.get("adoptedOn").asText()).isEqualTo("2016-01-10");
    }

    @Test
    void PUT에서_명시적_null이면_지워진다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        JsonNode r = rawPut(t, id, "{\"name\":\"초코\",\"species\":\"dog\",\"birthYear\":2015,"
                + "\"profileType\":null,\"birthDate\":null,\"adoptedOn\":null}", 200);
        assertThat(r.get("profileType").isNull()).isTrue();
        assertThat(r.get("birthDate").isNull()).isTrue();
        assertThat(r.get("adoptedOn").isNull()).isTrue();
        assertThat(r.get("birthYear").asInt()).isEqualTo(2015); // 연도는 그대로
    }

    @Test
    void 하나만_지우면_나머지는_유지된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        JsonNode r = rawPut(t, id, "{\"name\":\"초코\",\"species\":\"dog\",\"birthYear\":2015,\"adoptedOn\":null}", 200);
        assertThat(r.get("adoptedOn").isNull()).isTrue();
        assertThat(r.get("birthDate").asText()).isEqualTo("2015-03-02");
        assertThat(r.get("profileType").asText()).isEqualTo("senior");
    }

    @Test
    void 생일_날짜만_보내면_birthYear가_맞춰진다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        JsonNode r = create(t, body("birthDate", "2014-12-31"), 201);
        assertThat(r.get("birthYear").asInt()).isEqualTo(2014);
        assertThat(jdbc.sql("select birth_year from pets where id = :i").param("i", UUID.fromString(r.get("id").asText()))
                .query(Integer.class).single()).isEqualTo(2014);
    }

    @Test
    void 생일_연도가_birthYear와_다르면_400() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        assertValidation(create(t, body("birthYear", 2015, "birthDate", "2014-03-02"), 400));
        String id = createPet(t);
        assertValidation(call(jsonPut("/api/pets/" + id, body("birthYear", 2016, "birthDate", "2015-03-02")), t, 400));
    }

    @Test
    void 연도만_바꾸고_생일을_생략하면_저장된_생일_날짜가_해제된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        JsonNode r = rawPut(t, id, "{\"name\":\"초코\",\"species\":\"dog\",\"birthYear\":2013}", 200);
        assertThat(r.get("birthYear").asInt()).isEqualTo(2013);
        assertThat(r.get("birthDate").isNull()).isTrue();
        assertThat(r.get("adoptedOn").asText()).isEqualTo("2016-01-10"); // 입양일은 유지
        // birthYear 를 비워도 모순이므로 해제
        String id2 = call(get("/api/pets"), t, 200).get(0).get("id").asText();
        JsonNode r2 = call(jsonPut("/api/pets/" + id2, body("birthDate", "2013-05-05")), t, 200);
        JsonNode r3 = rawPut(t, id2, "{\"name\":\"초코\",\"species\":\"dog\"}", 200);
        assertThat(r2.get("birthYear").asInt()).isEqualTo(2013);
        assertThat(r3.get("birthDate").isNull()).isTrue();
    }

    @Test
    void 미래_날짜는_400() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        assertValidation(create(t, body("birthDate", "2026-10-14"), 400));
        assertValidation(create(t, body("adoptedOn", "2026-10-14"), 400));
    }

    @Test
    void 새벽_0시부터_3시59분에도_달력_날짜_오늘은_허용된다() throws Exception {
        setSeoulTime(2026, 10, 13, 3, 59); // 기록 날짜로는 10-12 이지만 달력 날짜는 10-13
        String t = newUserToken();
        JsonNode r = create(t, body("birthDate", "2026-10-13", "adoptedOn", "2026-10-13"), 201);
        assertThat(r.get("birthDate").asText()).isEqualTo("2026-10-13");
        assertThat(r.get("adoptedOn").asText()).isEqualTo("2026-10-13");
    }

    @Test
    void 달력_날짜는_app_zone_기준이라_UTC_날짜가_아니다() throws Exception {
        // 서울 10-13 00:30 = UTC 10-12 15:30. UTC 날짜 기준이면 10-13 이 미래로 거부된다.
        setSeoulTime(2026, 10, 13, 0, 30);
        String t = newUserToken();
        create(t, body("adoptedOn", "2026-10-13"), 201);
    }

    @Test
    void 존재하지_않는_날짜와_잘못된_형식은_400() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        assertValidation(create(t, body("birthDate", "2025-02-30"), 400));
        assertValidation(create(t, body("birthDate", "2025-02-29"), 400)); // 비윤년
        assertValidation(create(t, body("adoptedOn", "2025/02/10"), 400));
        assertValidation(create(t, body("adoptedOn", ""), 400));
        assertValidation(create(t, body("adoptedOn", 20250210), 400)); // 문자열이 아님
    }

    @Test
    void 윤년_2월29일은_허용된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        JsonNode r = create(t, body("birthDate", "2020-02-29"), 201);
        assertThat(r.get("birthYear").asInt()).isEqualTo(2020);
    }

    @Test
    void _1980년_이전은_400이고_1980_01_01은_허용된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        assertValidation(create(t, body("birthDate", "1979-12-31"), 400));
        assertValidation(create(t, body("adoptedOn", "1979-12-31"), 400));
        create(t, body("adoptedOn", "1980-01-01"), 201);
    }

    @Test
    void 입양일이_생일보다_빠르면_400이고_같은_날은_허용된다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        assertValidation(create(t, body("birthDate", "2020-05-05", "adoptedOn", "2020-05-04"), 400));
        JsonNode r = create(t, body("birthDate", "2020-05-05", "adoptedOn", "2020-05-05"), 201);
        String id = r.get("id").asText();
        // 저장된 입양일보다 늦은 생일로 바꾸면 400 (생략된 입양일도 함께 검사)
        assertValidation(call(jsonPut("/api/pets/" + id, body("birthYear", 2020, "birthDate", "2020-06-01")), t, 400));
    }

    @Test
    void 허용되지_않는_유형은_400() throws Exception {
        String t = newUserToken();
        assertValidation(create(t, body("profileType", "puppy"), 400));
        assertValidation(create(t, body("profileType", ""), 400));
    }

    @Test
    void profileType과_birthDate에_숫자_객체_불리언을_보내면_400이고_저장값은_그대로다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        for (String field : new String[] {"profileType", "birthDate"}) {
            for (String bad : new String[] {"123", "{\"a\":1}", "true", "[]"}) {
                String json = "{\"name\":\"초코\",\"species\":\"dog\",\"" + field + "\":" + bad + "}";
                assertValidation(rawPut(t, id, json, 400));
                assertValidation(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pets").contentType(MediaType.APPLICATION_JSON).content(json), t, 400));
            }
        }
        JsonNode got = call(get("/api/pets/" + id), t, 200);
        assertThat(got.get("profileType").asText()).isEqualTo("senior");
        assertThat(got.get("birthDate").asText()).isEqualTo("2015-03-02");
    }

    @Test
    void 검증에_실패한_PUT은_저장된_값을_바꾸지_않는다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        assertValidation(call(jsonPut("/api/pets/" + id, body("birthYear", 2015, "adoptedOn", "2030-01-01")), t, 400));
        JsonNode got = call(get("/api/pets/" + id), t, 200);
        assertThat(got.get("adoptedOn").asText()).isEqualTo("2016-01-10");
        assertThat(got.get("name").asText()).isEqualTo("초코");
    }

    @Test
    void DB_CHECK는_잘못된_값을_거부한다() throws Exception {
        String t = newUserToken();
        UUID id = UUID.fromString(createPet(t));
        String[] bad = {
            "update pets set profile_type = 'x' where id = :i",
            "update pets set birth_date = date '1979-01-01', birth_year = 1979 where id = :i",
            "update pets set adopted_on = date '1979-01-01' where id = :i",
            "update pets set birth_year = 2010, birth_date = date '2011-01-01' where id = :i",
            "update pets set birth_year = 2010, birth_date = date '2010-06-01', adopted_on = date '2010-05-01' where id = :i",
        };
        for (String sql : bad) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.sql(sql).param("i", id).update(), sql);
        }
    }

    @Test
    void 남의_pet에는_쓸_수_없고_값도_그대로다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String alice = newUserToken();
        String bob = newUserToken();
        String id = fullPet(alice);
        call(jsonPut("/api/pets/" + id, body("profileType", "first_time", "adoptedOn", null)), bob, 404);
        JsonNode got = call(get("/api/pets/" + id), alice, 200);
        assertThat(got.get("profileType").asText()).isEqualTo("senior");
        assertThat(got.get("adoptedOn").asText()).isEqualTo("2016-01-10");
    }

    @Test
    void 사진_변경은_프로필_값에_영향이_없다() throws Exception {
        setSeoulTime(2026, 10, 13, 12, 0);
        String t = newUserToken();
        String id = fullPet(t);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/pets/" + id + "/photo"), t, 204);
        assertThat(call(get("/api/pets/" + id), t, 200).get("birthDate").asText()).isEqualTo("2015-03-02");
    }
}
