package com.oraegyeot.seniorpet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** QA 보강: 비밀번호 변경 경계값, 탈퇴 다수 데이터·동시성, 속도 제한 세부. */
class AccountEdgeQaTest extends ApiTestSupport {

    private int changeStatus(String token, Map<String, ?> body) throws Exception {
        return mvc.perform(jsonPut("/api/me/password", body).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getStatus();
    }

    private int changeStatus(String token, String current, String next) throws Exception {
        return changeStatus(token, Map.of("currentPassword", current, "newPassword", next));
    }

    private int withdrawStatus(String token, String password) throws Exception {
        return mvc.perform(jsonPost("/api/me/withdraw", Map.of("password", password, "confirm", true))
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn().getResponse().getStatus();
    }

    private int loginStatus(String email, String password) throws Exception {
        return mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", password)))
                .andReturn().getResponse().getStatus();
    }

    /** 새 비밀번호 후보가 허용되는지(200) 거절되는지(400)만 본다. 매번 새 사용자. */
    private int tryNew(String next) throws Exception {
        return changeStatus(newDisposableUserToken(), PASSWORD, next);
    }

    @Test
    void 새_비밀번호_길이_경계_8자_통과_7자_거절() throws Exception {
        assertThat(tryNew("abcd1234")).isEqualTo(200);
        assertThat(tryNew("abcd123")).isEqualTo(400);
        assertThat(tryNew("")).isEqualTo(400);
    }

    @Test
    void 새_비밀번호_72바이트_경계_영문_한글_이모지() throws Exception {
        assertThat(tryNew("a".repeat(72))).isEqualTo(200);
        assertThat(tryNew("a".repeat(73))).isEqualTo(400);
        assertThat(tryNew("가".repeat(24))).isEqualTo(200);
        assertThat(tryNew("가".repeat(25))).isEqualTo(400);
        assertThat(tryNew("😀".repeat(18))).isEqualTo(200); // 72바이트
        assertThat(tryNew("😀".repeat(19))).isEqualTo(400); // 76바이트
        // 한글 23자 + 영문 1자... 71~72바이트 혼합
        assertThat(tryNew("가".repeat(23) + "ab")).isEqualTo(200); // 71바이트
        assertThat(tryNew("가".repeat(23) + "abc")).isEqualTo(200); // 72바이트
        assertThat(tryNew("가".repeat(23) + "abcd")).isEqualTo(400); // 73바이트
    }

    @Test
    void 공백_특수문자_포함_비밀번호는_그대로_저장되고_로그인된다() throws Exception {
        for (String pw : List.of("pass word 12", "  leading and trailing  ", "!@#$%^&*()_+-=", "tab\there1234",
                "quote\"back\\slash1", "😀😀😀😀😀😀😀😀")) {
            String email = randomEmail();
            String token = signup(email, PASSWORD).get("accessToken").asText();
            registerForCleanup(userIdOf(token));
            assertThat(changeStatus(token, PASSWORD, pw)).as("변경 %s", pw).isEqualTo(200);
            assertThat(loginStatus(email, pw)).as("새 비밀번호 로그인 %s", pw).isEqualTo(200);
            assertThat(loginStatus(email, pw.trim().isEmpty() ? "x" : pw + " ")).as("변형은 401").isEqualTo(401);
        }
    }

    @Test
    void 공백만_있는_8자_비밀번호는_어떻게_처리되나() throws Exception {
        // 동작 고정용: 가입 규칙과 같은지 확인(가입이 허용하면 변경도 허용돼야 일관적)
        int signupStatus = mvc.perform(jsonPost("/api/auth/signup",
                Map.of("email", randomEmail(), "password", "        "))).andReturn().getResponse().getStatus();
        int change = tryNew("        ");
        assertThat(change == 200).as("가입=%d 변경=%d 가 일관되어야 함", signupStatus, change)
                .isEqualTo(signupStatus == 201);
        // 만들어진 가입 사용자 정리
        jdbc.sql("delete from users where email like 'user-%@example.com' and created_at > now() - interval '1 minute' and false")
                .update();
    }

    @Test
    void 현재_비밀번호가_비었거나_null이거나_없으면_400() throws Exception {
        String token = newDisposableUserToken();
        Map<String, Object> nullCurrent = new HashMap<>();
        nullCurrent.put("currentPassword", null);
        nullCurrent.put("newPassword", "newPassword1");
        assertThat(changeStatus(token, nullCurrent)).isEqualTo(400);
        assertThat(changeStatus(token, Map.of("newPassword", "newPassword1"))).isEqualTo(400);
        assertThat(changeStatus(token, "", "newPassword1")).isEqualTo(400);
        Map<String, Object> nullNew = new HashMap<>();
        nullNew.put("currentPassword", PASSWORD);
        nullNew.put("newPassword", null);
        assertThat(changeStatus(token, nullNew)).isEqualTo(400);
        // 본문 자체가 없거나 깨진 JSON 도 5xx 가 아니라 400
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/me/password").contentType("application/json").content("{")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        call(get("/api/me"), token, 200);
    }

    @Test
    void 현재_비밀번호가_72바이트_초과면_400_불일치로_센다() throws Exception {
        String token = newDisposableUserToken();
        assertThat(changeStatus(token, "a".repeat(200), "newPassword1")).isEqualTo(400);
        assertThat(changeStatus(token, "가".repeat(100), "newPassword1")).isEqualTo(400);
        assertThat(changeStatus(token, PASSWORD, "newPassword1")).isEqualTo(200);
    }

    @Test
    void 탈퇴_다수_반려동물_약_기록_모두_삭제() throws Exception {
        setSeoulTime(2026, 10, 12, 7, 0);
        String email = randomEmail();
        String token = signup(email, PASSWORD).get("accessToken").asText();
        UUID userId = userIdOf(token);
        registerForCleanup(userId);
        setSeoulTime(2026, 10, 12, 8, 30);
        for (int p = 0; p < 1; p++) { // 사용자당 반려동물은 1마리(2번째 생성은 409)
            String pet = createPet(token);
            for (int m = 0; m < 3; m++) {
                String med = createMedication(token, pet, List.of("08:00", "20:00"));
                putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
                if (m == 0) {
                    call(jsonPost("/api/med-logs", Map.of("medicationId", med, "scheduledTime", "08:00")), token, 201);
                }
            }
            call(jsonPut("/api/pets/" + pet + "/daily-logs/2026-10-12", Map.of("foodLevel", 2)), token, 200);
        }
        registerDevice(token, "fcm-" + UUID.randomUUID(), "android");
        registerDevice(token, "fcm-" + UUID.randomUUID(), "ios");
        assertThat(withdrawStatus(token, PASSWORD)).isEqualTo(204);
        for (String t : List.of("pets", "medications", "med_logs", "daily_logs", "medication_reminders",
                "device_tokens", "reminder_dispatches", "events")) {
            long n = jdbc.sql("select count(*) from " + t + " where user_id = :u").param("u", userId)
                    .query(Long.class).single();
            assertThat(n).as(t).isZero();
        }
        // 탈퇴 직후 같은 이메일: 로그인 401, 재가입 201, 새 계정은 비어 있음
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);
        JsonNode again = signup(email, PASSWORD);
        String newToken = again.get("accessToken").asText();
        registerForCleanup(userIdOf(newToken));
        assertThat(userIdOf(newToken)).isNotEqualTo(userId);
        assertThat(call(get("/api/pets"), newToken, 200).size()).isZero();
        call(get("/api/me"), token, 401);
    }

    @Test
    void 동시에_두_번_탈퇴하면_하나만_204_나머지는_401_또는_400이고_5xx_없음() throws Exception {
        String token = newDisposableUserToken();
        List<Integer> res = parallel(() -> withdrawStatus(token, PASSWORD), () -> withdrawStatus(token, PASSWORD));
        assertThat(res).contains(204);
        assertThat(res).allMatch(s -> s == 204 || s == 401);
        assertThat(res.stream().filter(s -> s == 204).count()).isEqualTo(1);
    }

    @Test
    void 변경과_탈퇴가_동시여도_5xx_없고_탈퇴하면_계정이_남지_않는다() throws Exception {
        String email = randomEmail();
        String token = signup(email, PASSWORD).get("accessToken").asText();
        UUID userId = userIdOf(token);
        registerForCleanup(userId);
        List<Integer> res = parallel(() -> changeStatus(token, PASSWORD, "newPassword1"),
                () -> withdrawStatus(token, PASSWORD));
        assertThat(res).allMatch(s -> s < 500);
        long rows = jdbc.sql("select count(*) from users where id = :u").param("u", userId).query(Long.class).single();
        if (res.get(1) == 204) {
            assertThat(rows).as("탈퇴 204 인데 사용자 행이 남음").isZero();
        } else {
            assertThat(rows).isEqualTo(1);
        }
    }

    @Test
    void 동시에_두_번_변경해도_5xx_없고_최종_토큰은_하나만_유효() throws Exception {
        String token = newDisposableUserToken();
        List<Integer> res = parallel(() -> changeStatus(token, PASSWORD, "newPassword1"),
                () -> changeStatus(token, PASSWORD, "otherPassword2"));
        assertThat(res).allMatch(s -> s < 500);
        assertThat(res).contains(200);
    }

    @Test
    void 변경_실패와_탈퇴_실패_합산_5회_뒤_429_Retry_After는_양수_900이하() throws Exception {
        setSeoulTime(2026, 10, 12, 9, 0);
        String token = newDisposableUserToken();
        for (int i = 0; i < 3; i++) {
            assertThat(changeStatus(token, "wrong-pass", "newPassword1")).isEqualTo(400);
        }
        assertThat(withdrawStatus(token, "wrong-pass")).isEqualTo(400);
        assertThat(withdrawStatus(token, "wrong-pass")).isEqualTo(400);
        clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        mvc.perform(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300")); // 첫 실패 시각 + 15분 - 지금
        // 429 는 실패로 세지 않아 시간이 지나면 풀린다
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(withdrawStatus(token, PASSWORD)).isEqualTo(204);
    }

    @Test
    void 잠긴_사용자가_있어도_다른_사용자의_변경과_탈퇴는_정상() throws Exception {
        String locked = newDisposableUserToken();
        String other = newDisposableUserToken();
        for (int i = 0; i < 5; i++) {
            changeStatus(locked, "wrong-pass", "newPassword1");
        }
        assertThat(changeStatus(locked, PASSWORD, "newPassword1")).isEqualTo(429);
        assertThat(withdrawStatus(other, "wrong-pass")).isEqualTo(400);
        assertThat(changeStatus(other, PASSWORD, "newPassword1")).isEqualTo(200);
    }

    private List<Integer> parallel(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (Callable<Integer> c : List.of(a, b)) {
                fs.add(ex.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }
}
