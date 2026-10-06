package com.oraegyeot.seniorpet.today;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 소유자 격리: 다른 사용자의 pet / medication / med-log / daily-log / photo 에 접근하면 404 (보안 규칙 7).
 */
class TodayOwnershipTest extends ApiTestSupport {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private void assertNotFound(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 다른_사용자의_리소스는_모두_404() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        String pet = createPet(alice);
        String med = createMedication(alice, pet, List.of("08:00"));
        String medLog = call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")),
                alice, 201).get("id").asText();
        String recordDate = call(get("/api/pets/" + pet + "/today"), alice, 200).get("recordDate").asText();
        Map<String, Object> daily = Map.of("symptoms", List.of(), "symptomsNone", true);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/" + recordDate, daily), alice, 200);
        call(multipart("/api/pets/" + pet + "/photo")
                .file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .with(r -> { r.setMethod("PUT"); return r; }), alice, 200);

        // pet
        assertNotFound(call(get("/api/pets/" + pet), bob, 404));
        assertNotFound(call(jsonPut("/api/pets/" + pet, Map.of("name", "뺏기", "species", "cat")), bob, 404));
        assertNotFound(call(get("/api/pets/" + pet + "/today"), bob, 404));
        // medication
        assertNotFound(call(get("/api/pets/" + pet + "/medications"), bob, 404));
        assertNotFound(call(jsonPost("/api/pets/" + pet + "/medications",
                Map.of("name", "약", "times", List.of("09:00"))), bob, 404));
        assertNotFound(call(jsonPut("/api/medications/" + med,
                Map.of("name", "약", "times", List.of("09:00"))), bob, 404));
        assertNotFound(call(delete("/api/medications/" + med), bob, 404));
        // med-log: 남의 약으로 체크, 남의 체크 취소
        assertNotFound(call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), bob, 404));
        assertNotFound(call(delete("/api/med-logs/" + medLog), bob, 404));
        // daily-log
        assertNotFound(call(jsonPut("/api/pets/" + pet + "/daily-logs/" + recordDate, daily), bob, 404));
        // photo
        assertNotFound(call(get("/api/pets/" + pet + "/photo"), bob, 404));
        assertNotFound(call(delete("/api/pets/" + pet + "/photo"), bob, 404));
        assertNotFound(call(multipart("/api/pets/" + pet + "/photo")
                .file(new MockMultipartFile("file", "b.png", "image/png", PNG))
                .with(r -> { r.setMethod("PUT"); return r; }), bob, 404));

        // alice 의 데이터는 그대로다
        JsonNode day = call(get("/api/pets/" + pet + "/today"), alice, 200);
        assertThat(day.get("doses").get(0).get("taken").asBoolean()).isTrue();
        assertThat(day.get("dailyLog").isNull()).isFalse();
        assertThat(call(get("/api/pets/" + pet), alice, 200).get("name").asText()).isEqualTo("초코");
        assertThat(call(get("/api/pets/" + pet), alice, 200).get("hasPhoto").asBoolean()).isTrue();
        mvc.perform(get("/api/pets/" + pet + "/photo").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PNG));
    }

    @Test
    void 없는_리소스와_삭제한_약은_404() throws Exception {
        String alice = newUserToken();
        String pet = createPet(alice);
        String med = createMedication(alice, pet, List.of("08:00"));
        call(delete("/api/medications/" + med), alice, 204);
        assertNotFound(call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), alice, 404));
        assertNotFound(call(delete("/api/medications/" + med), alice, 404));
        assertNotFound(call(delete("/api/med-logs/" + java.util.UUID.randomUUID()), alice, 404));
        // 사진이 없으면 GET photo 는 404
        assertNotFound(call(get("/api/pets/" + pet + "/photo"), alice, 404));
    }
}
