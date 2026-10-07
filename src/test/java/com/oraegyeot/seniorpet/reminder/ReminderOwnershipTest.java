package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 소유자 격리: 다른 사용자의 약 알림·기기 토큰에 접근하면 404, 본문의 userId 는 무시(보안 규칙 1~7). */
class ReminderOwnershipTest extends ApiTestSupport {

    private static void assertNotFound(JsonNode err) {
        assertThat(err.get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 다른_사용자의_약_알림은_404() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String med = createMedication(alice, createPet(alice), List.of("08:00"));
        putReminder(alice, med, Map.of("enabled", true, "repeat", "daily"));

        assertNotFound(call(get("/api/medications/" + med + "/reminder"), bob, 404));
        assertNotFound(call(jsonPut("/api/medications/" + med + "/reminder",
                Map.of("enabled", false, "repeat", "daily")), bob, 404));

        // alice 의 설정은 그대로다
        assertThat(call(get("/api/medications/" + med + "/reminder"), alice, 200).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void 다른_사용자의_기기_삭제는_404() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String device = registerDevice(alice, "fcm-" + UUID.randomUUID(), "android");

        assertNotFound(call(delete("/api/devices/" + device), bob, 404));
        call(delete("/api/devices/" + device), alice, 204); // 본인은 삭제 가능(= bob 시도로 지워지지 않았음)
    }

    @Test
    void 본문의_userId는_무시되고_로그인_사용자로_저장된다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        UUID aliceId = userIdOf(alice);
        UUID bobId = userIdOf(bob);
        String med = createMedication(alice, createPet(alice), List.of("08:00"));

        call(jsonPut("/api/medications/" + med + "/reminder",
                Map.of("enabled", true, "repeat", "daily", "userId", bobId.toString())), alice, 200);
        String device = call(jsonPut("/api/devices",
                Map.of("token", "fcm-" + UUID.randomUUID(), "platform", "ios", "userId", bobId.toString())), alice, 200)
                .get("id").asText();

        UUID reminderOwner = jdbc.sql("select user_id from medication_reminders where medication_id = :id")
                .param("id", UUID.fromString(med)).query(UUID.class).single();
        UUID deviceOwner = jdbc.sql("select user_id from device_tokens where id = :id")
                .param("id", UUID.fromString(device)).query(UUID.class).single();
        assertThat(reminderOwner).isEqualTo(aliceId);
        assertThat(deviceOwner).isEqualTo(aliceId);
        assertNotFound(call(delete("/api/devices/" + device), bob, 404));
    }
}
