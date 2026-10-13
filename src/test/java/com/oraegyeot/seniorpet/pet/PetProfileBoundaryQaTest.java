package com.oraegyeot.seniorpet.pet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA 추가: 서울 시간대 달력 날짜 경계(04:00, 23:59, 자정 직후)와 연말·연초. */
class PetProfileBoundaryQaTest extends ApiTestSupport {

    private Map<String, Object> body(String key, String value) {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "초코");
        b.put("species", "dog");
        b.put(key, value);
        return b;
    }

    private JsonNode create(String t, Map<String, Object> b, int expected) throws Exception {
        return call(jsonPost("/api/pets", b), t, expected);
    }

    @Test
    void 새벽_4시_정각에도_오늘은_허용되고_내일은_400() throws Exception {
        setSeoulTime(2026, 10, 13, 4, 0);
        create(newUserToken(), body("birthDate", "2026-10-13"), 201);
        assertThat(create(newUserToken(), body("birthDate", "2026-10-14"), 400).get("code").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 밤_23시59분에도_오늘은_허용되고_내일은_400() throws Exception {
        setSeoulTime(2026, 10, 13, 23, 59);
        create(newUserToken(), body("adoptedOn", "2026-10-13"), 201);
        assertThat(create(newUserToken(), body("adoptedOn", "2026-10-14"), 400).get("code").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 자정_직후에는_어제_날짜가_과거이고_오늘이_허용된다() throws Exception {
        setSeoulTime(2026, 10, 14, 0, 0);
        create(newUserToken(), body("birthDate", "2026-10-13"), 201);
        create(newUserToken(), body("birthDate", "2026-10-14"), 201);
        create(newUserToken(), body("birthDate", "2026-10-15"), 400);
    }

    @Test
    void 연말_연초_서울_날짜_경계() throws Exception {
        setSeoulTime(2027, 1, 1, 0, 10); // UTC 로는 2026-12-31
        create(newUserToken(), body("birthDate", "2027-01-01"), 201);
        create(newUserToken(), body("birthDate", "2027-01-02"), 400);
        setSeoulTime(2026, 12, 31, 23, 50);
        create(newUserToken(), body("birthDate", "2026-12-31"), 201);
        create(newUserToken(), body("birthDate", "2027-01-01"), 400);
    }
}
