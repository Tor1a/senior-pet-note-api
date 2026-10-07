package com.oraegyeot.seniorpet.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 기기 토큰 API(PUT /api/devices, DELETE /api/devices/{id}). */
class DeviceApiTest extends ApiTestSupport {

    private static String newFcmToken() {
        return "fcm-" + UUID.randomUUID();
    }

    private long countByToken(String fcmToken) {
        return jdbc.sql("select count(*) from device_tokens where token = :t").param("t", fcmToken)
                .query(Long.class).single();
    }

    private long countByUser(UUID userId) {
        return jdbc.sql("select count(*) from device_tokens where user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    private void assertBadRequest(String token, Map<String, Object> body) throws Exception {
        assertThat(call(jsonPut("/api/devices", body), token, 400).get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 등록하면_200이고_토큰_값은_응답에_없다() throws Exception {
        setSeoulTime(2026, 10, 6, 9, 0);
        String token = newDisposableUserToken();
        JsonNode d = call(jsonPut("/api/devices", Map.of("token", newFcmToken(), "platform", "android")), token, 200);
        assertThat(d.get("id").asText()).isNotBlank();
        assertThat(d.get("platform").asText()).isEqualTo("android");
        assertThat(d.get("createdAt").asText()).isEqualTo("2026-10-06T00:00:00Z");
        assertThat(d.get("lastSeenAt").asText()).isEqualTo("2026-10-06T00:00:00Z");
        assertThat(d.has("token")).isFalse();
        assertThat(d.size()).isEqualTo(4);
    }

    @Test
    void 같은_토큰_재등록은_행_1개이고_lastSeenAt만_갱신() throws Exception {
        String token = newDisposableUserToken();
        String fcm = newFcmToken();
        setSeoulTime(2026, 10, 6, 9, 0);
        JsonNode first = call(jsonPut("/api/devices", Map.of("token", fcm, "platform", "android")), token, 200);
        setSeoulTime(2026, 10, 7, 9, 0);
        JsonNode second = call(jsonPut("/api/devices", Map.of("token", fcm, "platform", "ios")), token, 200);

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("createdAt").asText()).isEqualTo("2026-10-06T00:00:00Z");
        assertThat(second.get("lastSeenAt").asText()).isEqualTo("2026-10-07T00:00:00Z");
        assertThat(second.get("platform").asText()).isEqualTo("ios");
        assertThat(countByToken(fcm)).isEqualTo(1);
    }

    @Test
    void 다른_사용자가_같은_토큰을_등록하면_현재_사용자로_이전된다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String fcm = newFcmToken();
        String aliceDevice = registerDevice(alice, fcm, "android");

        JsonNode bobDevice = call(jsonPut("/api/devices", Map.of("token", fcm, "platform", "android")), bob, 200);
        assertThat(bobDevice.get("id").asText()).isNotEqualTo(aliceDevice);
        assertThat(bobDevice.toString()).doesNotContain(userIdOf(alice).toString()); // alice 정보 노출 없음

        assertThat(countByToken(fcm)).isEqualTo(1);
        assertThat(countByUser(userIdOf(alice))).isZero();
        call(delete("/api/devices/" + aliceDevice), alice, 404); // alice 에게서 사라짐
    }

    @Test
    void 열한번째_등록은_가장_오래된_토큰을_지운다() throws Exception {
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < DeviceTokenService.MAX_TOKENS_PER_USER; i++) {
            setSeoulTime(2026, 10, 6, 9, i);
            ids.add(registerDevice(token, newFcmToken(), "web"));
        }
        // 첫 번째 토큰을 재등록해 가장 최근으로 만든다 → 이제 가장 오래된 것은 두 번째
        String firstFcm = jdbc.sql("select token from device_tokens where id = :id")
                .param("id", UUID.fromString(ids.get(0))).query(String.class).single();
        setSeoulTime(2026, 10, 6, 10, 0);
        registerDevice(token, firstFcm, "web");
        assertThat(countByUser(userId)).isEqualTo(10);

        setSeoulTime(2026, 10, 6, 11, 0);
        String eleventh = registerDevice(token, newFcmToken(), "web");

        assertThat(countByUser(userId)).isEqualTo(10);
        call(delete("/api/devices/" + ids.get(1)), token, 404); // 가장 오래된 것이 지워짐
        call(delete("/api/devices/" + ids.get(0)), token, 204);
        call(delete("/api/devices/" + eleventh), token, 204);
    }

    @Test
    void 검증_위반은_400() throws Exception {
        String token = newDisposableUserToken();
        assertBadRequest(token, Map.of("token", newFcmToken(), "platform", "windows"));
        assertBadRequest(token, Map.of("token", newFcmToken()));
        assertBadRequest(token, Map.of("platform", "android"));
        assertBadRequest(token, Map.of("token", "", "platform", "android"));
        assertBadRequest(token, Map.of("token", "has space", "platform", "android"));
        assertBadRequest(token, Map.of("token", "tab\tinside", "platform", "android"));
        assertBadRequest(token, Map.of("token", "x".repeat(4097), "platform", "android"));
        Map<String, Object> nullToken = new HashMap<>();
        nullToken.put("token", null);
        nullToken.put("platform", "ios");
        assertBadRequest(token, nullToken);
        // 경계값 4096자는 허용
        call(jsonPut("/api/devices", Map.of("token", "y".repeat(4095) + UUID.randomUUID().toString().charAt(0),
                "platform", "ios")), token, 200);
    }

    @Test
    void 삭제는_204_두번째는_404() throws Exception {
        String token = newDisposableUserToken();
        String device = registerDevice(token, newFcmToken(), "ios");
        call(delete("/api/devices/" + device), token, 204);
        call(delete("/api/devices/" + device), token, 404);
        call(delete("/api/devices/" + UUID.randomUUID()), token, 404);
    }

    @Test
    void 로그인_없이는_401() throws Exception {
        mvc.perform(jsonPut("/api/devices", Map.of("token", newFcmToken(), "platform", "ios")))
                .andExpect(status().isUnauthorized());
    }
}
