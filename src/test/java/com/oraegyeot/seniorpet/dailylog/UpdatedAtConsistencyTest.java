package com.oraegyeot.seniorpet.dailylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 응답의 updatedAt 은 DB 에 저장된 값(트리거가 덮어쓴 값)이어야 한다. docs/api-undo.md 3장.
 * 테스트 시계와 DB now() 의 차이에 의존하지 않도록 "응답 == 이어서 조회한 값" 만 단언한다.
 */
class UpdatedAtConsistencyTest extends ApiTestSupport {

    private String url(String pet) {
        return "/api/pets/" + pet + "/daily-logs/2026-10-12";
    }

    private String todayUpdatedAt(String t, String pet) throws Exception {
        return call(get("/api/pets/" + pet + "/today"), t, 200).get("dailyLog").get("updatedAt").asText();
    }

    @Test
    void 일일기록_저장_응답의_updatedAt_은_이어서_조회한_today_와_정확히_같다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        setSeoulTime(2026, 10, 12, 12, 0);

        String first = call(jsonPut(url(pet), Map.of("foodLevel", 2, "symptoms", List.of(), "symptomsNone", true)), t, 200)
                .get("updatedAt").asText(); // INSERT
        assertThat(todayUpdatedAt(t, pet)).isEqualTo(first);

        for (int i = 1; i <= 2; i++) { // UPDATE 2회 연속
            clock.set(clock.instant().plusSeconds(5));
            String saved = call(jsonPut(url(pet), Map.of("foodLevel", i, "symptoms", List.of(), "symptomsNone", true)), t, 200)
                    .get("updatedAt").asText();
            assertThat(todayUpdatedAt(t, pet)).isEqualTo(saved);
        }
    }

    @Test
    void 알림규칙_PUT_응답의_updatedAt_은_GET_과_같다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        String med = createMedication(t, pet, List.of("08:00"));
        Map<String, Object> body = Map.of("enabled", true, "repeat", "daily");

        for (int i = 0; i < 3; i++) { // INSERT 후 UPDATE 2회
            JsonNode put = putReminder(t, med, body);
            JsonNode got = call(get("/api/medications/" + med + "/reminder"), t, 200);
            assertThat(got.get("updatedAt").asText()).isEqualTo(put.get("updatedAt").asText());
        }
    }

    @Test
    void 반려동물_수정_응답의_updatedAt_은_GET_과_같다() throws Exception {
        String t = newUserToken();
        String pet = createPet(t);
        for (String name : List.of("초코2", "초코3")) {
            JsonNode put = call(jsonPut("/api/pets/" + pet, Map.of("name", name, "species", "dog")), t, 200);
            JsonNode got = call(get("/api/pets/" + pet), t, 200);
            assertThat(got.get("updatedAt").asText()).isEqualTo(put.get("updatedAt").asText());
        }
    }
}
