package com.oraegyeot.seniorpet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 비밀번호 확인 실패 속도 제한: 15분 5회 → 429 TOO_MANY_ATTEMPTS (비밀번호 변경·탈퇴 공통). */
class PasswordAttemptLimitTest extends ApiTestSupport {

    private JsonNode wrongChange(String token, int expected) throws Exception {
        return call(jsonPut("/api/me/password", Map.of("currentPassword", "wrong-pass", "newPassword", "newPassword1")),
                token, expected);
    }

    private JsonNode wrongWithdraw(String token, int expected) throws Exception {
        return call(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass", "confirm", true)),
                token, expected);
    }

    @Test
    void 비밀번호_변경은_5회_실패_뒤_429와_Retry_After() throws Exception {
        String token = newDisposableUserToken();
        for (int i = 0; i < 5; i++) {
            assertThat(wrongChange(token, 400).get("code").asText()).isEqualTo("CURRENT_PASSWORD_MISMATCH");
        }
        JsonNode err = wrongChange(token, 429);
        assertThat(err.get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        // 맞는 비밀번호여도 잠긴 동안은 429
        mvc.perform(jsonPut("/api/me/password", Map.of("currentPassword", PASSWORD, "newPassword", "newPassword1"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"));
    }

    @Test
    void 두_API가_같은_카운터를_쓴다() throws Exception {
        String token = newDisposableUserToken();
        for (int i = 0; i < 3; i++) {
            wrongChange(token, 400);
        }
        wrongWithdraw(token, 400);
        wrongWithdraw(token, 400);
        assertThat(wrongWithdraw(token, 429).get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(wrongChange(token, 429).get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        // 잠겨도 사용자 데이터는 그대로
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me"), token, 200);
    }

    @Test
    void 사용자별로_따로_센다() throws Exception {
        String a = newDisposableUserToken();
        String b = newDisposableUserToken();
        for (int i = 0; i < 5; i++) {
            wrongChange(a, 400);
        }
        wrongChange(a, 429);
        wrongChange(b, 400);
    }

    @Test
    void 정확히_15분이_지나면_풀린다() throws Exception {
        String token = newDisposableUserToken();
        setSeoulTime(2026, 10, 12, 9, 0);
        for (int i = 0; i < 5; i++) {
            wrongChange(token, 400);
        }
        clock.set(clock.instant().plus(Duration.ofMinutes(14)).plusSeconds(59));
        wrongChange(token, 429);
        clock.set(clock.instant().plusSeconds(1)); // 첫 실패로부터 15분
        wrongChange(token, 400); // 풀렸다(다시 실패 1회)
    }

    @Test
    void 오래된_실패는_창에서_빠진다() throws Exception {
        String token = newDisposableUserToken();
        setSeoulTime(2026, 10, 12, 9, 0);
        for (int i = 0; i < 4; i++) {
            wrongChange(token, 400);
        }
        clock.set(clock.instant().plus(Duration.ofMinutes(16)));
        for (int i = 0; i < 4; i++) {
            wrongChange(token, 400); // 이전 4회는 창 밖이라 이번이 1~4회째
        }
        wrongChange(token, 400); // 5회째 실패
        wrongChange(token, 429);
    }

    @Test
    void 성공하면_카운터가_초기화된다() throws Exception {
        String token = newDisposableUserToken();
        for (int i = 0; i < 4; i++) {
            wrongChange(token, 400);
        }
        String fresh = call(jsonPut("/api/me/password", Map.of("currentPassword", PASSWORD, "newPassword", "newPassword1")),
                token, 200).get("accessToken").asText();
        for (int i = 0; i < 4; i++) {
            call(jsonPut("/api/me/password", Map.of("currentPassword", "wrong-pass", "newPassword", "another1234")),
                    fresh, 400);
        }
        // 초기화되지 않았다면 여기서 429 였을 것(4+4=8회)
        call(jsonPut("/api/me/password", Map.of("currentPassword", "wrong-pass", "newPassword", "another1234")),
                fresh, 400);
    }

    @Test
    void 확인_플래그_없는_탈퇴_요청은_실패로_세지_않는다() throws Exception {
        String token = newDisposableUserToken();
        for (int i = 0; i < 6; i++) {
            call(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass")), token, 400);
        }
        wrongWithdraw(token, 400);
    }

    @Test
    void 속도_제한_응답에_미인증_요청은_401() throws Exception {
        mvc.perform(post("/api/me/withdraw").contentType("application/json")
                        .content("{\"password\":\"x\",\"confirm\":true}"))
                .andExpect(status().isUnauthorized());
    }
}
