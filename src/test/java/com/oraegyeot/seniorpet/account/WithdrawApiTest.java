package com.oraegyeot.seniorpet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.reminder.ReminderDispatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/** POST /api/me/withdraw (docs/api-account.md 2절). 알림 테스트처럼 임시 사용자를 쓰고 끝나면 지운다. */
class WithdrawApiTest extends ApiTestSupport {

    /** user_id 컬럼으로 직접 지워지는 테이블 전부(V1·V2 의 users 자식) */
    private static final List<String> TABLES = List.of("pets", "medications", "med_logs", "daily_logs", "events",
            "medication_reminders", "device_tokens", "reminder_dispatches");

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @Value("${app.photo.dir}")
    String photoDir;

    @Autowired
    private ReminderDispatcher dispatcher;

    private record Data(String token, UUID userId, String email, String petId, String medId, String fcm) {
    }

    /** 모든 자식 테이블에 행이 생기도록 데이터를 채운다(사진 파일 포함). */
    private Data fullUser() throws Exception {
        setSeoulTime(2026, 10, 12, 7, 0);
        String email = randomEmail();
        String token = signup(email, PASSWORD).get("accessToken").asText();
        UUID userId = userIdOf(token);
        // 테스트 도중 실패해도 지워지도록 등록(탈퇴 후에는 0행 삭제라 무해)
        registerForCleanup(userId);
        String pet = createPet(token);
        String med = createMedication(token, pet, List.of("08:00"));
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "android");
        call(jsonPost("/api/events", Map.of("name", "today_opened")), token, 202);
        setSeoulTime(2026, 10, 12, 8, 0);
        dispatcher.dispatchDue(); // reminder_dispatches 행 생성
        call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), token, 201);
        call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-12", Map.of("foodLevel", 2, "memo", "잘 먹음")),
                token, 200);
        mvc.perform(multipart("/api/pets/" + pet + "/photo")
                        .file(new MockMultipartFile("file", "p.bin", "image/jpeg", JPEG))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
        return new Data(token, userId, email, pet, med, fcm);
    }

    private long count(String table, UUID userId) {
        return jdbc.sql("select count(*) from " + table + " where user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    private long userRows(UUID userId) {
        return jdbc.sql("select count(*) from users where id = :u").param("u", userId).query(Long.class).single();
    }

    private Path photoFolder(UUID userId) {
        return Path.of(photoDir, userId.toString());
    }

    private void withdraw(Data d, int expected) throws Exception {
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), d.token(), expected);
    }

    @Test
    void 탈퇴하면_모든_관련_행과_사진이_사라지고_204() throws Exception {
        Data d = fullUser();
        for (String table : TABLES) {
            assertThat(count(table, d.userId())).as(table + " 탈퇴 전").isPositive();
        }
        assertThat(Files.list(photoFolder(d.userId())).count()).isEqualTo(1);

        withdraw(d, 204);

        assertThat(userRows(d.userId())).isZero();
        for (String table : TABLES) {
            assertThat(count(table, d.userId())).as(table + " 탈퇴 후").isZero();
        }
        assertThat(Files.exists(photoFolder(d.userId()))).isFalse();
    }

    @Test
    void 탈퇴_뒤_같은_토큰은_401이고_로그인도_안_된다() throws Exception {
        Data d = fullUser();
        withdraw(d, 204);
        call(get("/api/me"), d.token(), 401);
        call(get("/api/pets"), d.token(), 401);
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), d.token(), 401);
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", d.email(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_이메일로_다시_가입할_수_있고_새_계정은_비어_있다() throws Exception {
        Data d = fullUser();
        withdraw(d, 204);
        JsonNode again = signup(d.email(), PASSWORD);
        UUID newId = UUID.fromString(again.get("user").get("id").asText());
        registerForCleanup(newId);
        assertThat(newId).isNotEqualTo(d.userId());
        call(get("/api/pets"), again.get("accessToken").asText(), 200).forEach(p -> {
            throw new AssertionError("새 계정에 옛 데이터가 있다");
        });
        call(get("/api/me"), d.token(), 401); // 옛 토큰은 새 계정에도 통하지 않는다
    }

    @Test
    void 비밀번호가_틀리면_400이고_데이터가_그대로다() throws Exception {
        Data d = fullUser();
        JsonNode err = call(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass", "confirm", true)),
                d.token(), 400);
        assertThat(err.get("code").asText()).isEqualTo("CURRENT_PASSWORD_MISMATCH");
        assertThat(userRows(d.userId())).isEqualTo(1);
        for (String table : TABLES) {
            assertThat(count(table, d.userId())).as(table).isPositive();
        }
        assertThat(Files.exists(photoFolder(d.userId()))).isTrue();
        call(get("/api/me"), d.token(), 200);
    }

    @Test
    void 확인_플래그가_없거나_false면_400이고_데이터가_그대로다() throws Exception {
        Data d = fullUser();
        assertThat(call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD)), d.token(), 400)
                .get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", false)), d.token(), 400)
                .get("code").asText()).isEqualTo("VALIDATION_ERROR");
        call(jsonPost("/api/me/withdraw", Map.of("confirm", true)), d.token(), 400);
        assertThat(userRows(d.userId())).isEqualTo(1);
        assertThat(count("pets", d.userId())).isPositive();
    }

    @Test
    void 다른_사용자의_데이터와_사진은_영향이_없다() throws Exception {
        Data a = fullUser();
        Data b = fullUser();
        withdraw(a, 204);
        assertThat(userRows(b.userId())).isEqualTo(1);
        for (String table : TABLES) {
            assertThat(count(table, b.userId())).as(table).isPositive();
        }
        assertThat(Files.exists(photoFolder(b.userId()))).isTrue();
        call(get("/api/me"), b.token(), 200);
    }

    @Test
    void 사진이_없는_사용자도_탈퇴된다() throws Exception {
        String token = newDisposableUserToken();
        UUID id = userIdOf(token);
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), token, 204);
        assertThat(userRows(id)).isZero();
    }

    @Test
    void 탈퇴_뒤_알림_발송은_오류_없이_아무것도_보내지_않는다() throws Exception {
        Data d = fullUser();
        withdraw(d, 204);
        pushSender.reset();
        setSeoulTime(2026, 10, 13, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(d.fcm())).isEmpty();
        assertThat(count("reminder_dispatches", d.userId())).isZero();
    }
}
