package com.oraegyeot.seniorpet.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 소유자 격리: 남의 pet 의 지난 기록은 404, 응답에 다른 사용자 데이터가 섞이지 않는다. */
class HistoryOwnershipTest extends ApiTestSupport {

    @Test
    void 남의_pet은_404_없는_pet도_404() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String pet = createPet(alice);

        JsonNode err = call(get("/api/pets/" + pet + "/daily-logs"), bob, 404);
        assertThat(err.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call(get("/api/pets/" + UUID.randomUUID() + "/daily-logs"), bob, 404)
                .get("code").asText()).isEqualTo("NOT_FOUND");
        // 잘못된 파라미터여도 남의 pet 이면 존재 여부를 노출하지 않고 404
        assertThat(call(get("/api/pets/" + pet + "/daily-logs?from=abc"), bob, 404)
                .get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 내_응답에_다른_사용자의_기록과_투약이_섞이지_않는다() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String alicePet = createPet(alice);
        String bobPet = createPet(bob);

        setSeoulTime(2026, 10, 7, 12, 0);
        String med = createMedication(alice, alicePet, List.of("08:00"));
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), alice, 201);
        call(jsonPut("/api/pets/" + alicePet + "/daily-logs/2026-10-07",
                Map.of("symptoms", List.of(), "symptomsNone", true, "memo", "앨리스 비밀")), alice, 200);

        // userId/petId 쿼리 파라미터는 무시된다
        JsonNode res = call(get("/api/pets/" + bobPet + "/daily-logs?from=2026-10-06&to=2026-10-07"
                + "&userId=" + alicePet + "&petId=" + alicePet), bob, 200);
        assertThat(res.toString()).doesNotContain("앨리스 비밀").doesNotContain(alicePet);
        for (JsonNode d : res.get("days")) {
            assertThat(d.get("dailyLog").isNull()).isTrue();
            assertThat(d.get("medication").get("scheduledCount").asInt()).isZero();
            assertThat(d.get("medication").get("takenCount").asInt()).isZero();
        }
        JsonNode mine = call(get("/api/pets/" + alicePet + "/daily-logs?from=2026-10-07&to=2026-10-07"), alice, 200);
        assertThat(mine.get("days").get(0).get("dailyLog").get("memo").asText()).isEqualTo("앨리스 비밀");
        assertThat(mine.get("days").get(0).get("medication").get("takenCount").asInt()).isEqualTo(1);
    }
}
