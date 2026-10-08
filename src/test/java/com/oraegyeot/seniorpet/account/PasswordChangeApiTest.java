package com.oraegyeot.seniorpet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.security.JwtService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** PUT /api/me/password (docs/api-account.md 1절). */
class PasswordChangeApiTest extends ApiTestSupport {

    private static final String NEW_PASSWORD = "brandNewPass1";

    @Autowired
    private JwtService jwtService;

    private JsonNode change(String token, String current, String next, int expected) throws Exception {
        return call(jsonPut("/api/me/password", Map.of("currentPassword", current, "newPassword", next)),
                token, expected);
    }

    private void login(String email, String password, int expected) throws Exception {
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", password)))
                .andExpect(status().is(expected));
    }

    private String code(JsonNode body) {
        return body.get("code").asText();
    }

    @Test
    void 성공하면_새_토큰을_주고_새_비밀번호로만_로그인된다() throws Exception {
        String email = randomEmail();
        JsonNode signed = signup(email, PASSWORD);
        String old = signed.get("accessToken").asText();
        try {
            JsonNode res = change(old, PASSWORD, NEW_PASSWORD, 200);
            String fresh = res.get("accessToken").asText();
            assertThat(res.fieldNames()).toIterable().containsExactly("accessToken");

            // 새 토큰은 유효, 이전 토큰은 401
            call(get("/api/me"), fresh, 200);
            call(get("/api/me"), old, 401);
            login(email, NEW_PASSWORD, 200);
            login(email, PASSWORD, 401);
        } finally {
            jdbc.sql("delete from users where email = :e").param("e", email).update();
        }
    }

    @Test
    void 두_번_바꾸면_첫_번째_새_토큰도_무효() throws Exception {
        String token = newDisposableUserToken();
        String second = change(token, PASSWORD, NEW_PASSWORD, 200).get("accessToken").asText();
        String third = change(second, NEW_PASSWORD, "thirdPass123", 200).get("accessToken").asText();
        call(get("/api/me"), second, 401);
        call(get("/api/me"), third, 200);
    }

    @Test
    void 버전_클레임이_없는_기존_토큰은_0으로_보고_유효하다() throws Exception {
        String token = newDisposableUserToken();
        // 이 사용자는 아직 비밀번호를 바꾼 적이 없어 token_version = 0, 위조 없이 ver 만 빠진 토큰을 직접 만든다
        var userId = userIdOf(token);
        String legacy = io.jsonwebtoken.Jwts.builder().subject(userId.toString())
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-only-secret-key-0123456789-abcdefghijklmnopqrstuvwxyz"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        call(get("/api/me"), legacy, 200);
        change(token, PASSWORD, NEW_PASSWORD, 200);
        call(get("/api/me"), legacy, 401); // 버전이 올라가면 ver 없는 토큰(=0)은 무효
    }

    @Test
    void 현재_비밀번호가_틀리면_401이_아니라_400_CURRENT_PASSWORD_MISMATCH() throws Exception {
        String token = newDisposableUserToken();
        JsonNode err = change(token, "wrong-password", NEW_PASSWORD, 400);
        assertThat(code(err)).isEqualTo("CURRENT_PASSWORD_MISMATCH");
        call(get("/api/me"), token, 200); // 토큰은 그대로 유효
        login(jdbcEmail(token), PASSWORD, 200); // 비밀번호도 그대로
    }

    private String jdbcEmail(String token) throws Exception {
        return call(get("/api/me"), token, 200).get("email").asText();
    }

    @Test
    void 새_비밀번호_규칙_위반은_400_VALIDATION_ERROR() throws Exception {
        String token = newDisposableUserToken();
        assertThat(code(change(token, PASSWORD, "short12", 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(change(token, PASSWORD, "a".repeat(73), 400))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(call(jsonPut("/api/me/password", Map.of("currentPassword", PASSWORD)), token, 400)))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(code(change(token, "", NEW_PASSWORD, 400))).isEqualTo("VALIDATION_ERROR");
        // 규칙 위반은 비밀번호 확인 실패로 세지 않는다
        change(token, PASSWORD, NEW_PASSWORD, 200);
    }

    @Test
    void 현재와_같은_비밀번호는_400_VALIDATION_ERROR() throws Exception {
        String token = newDisposableUserToken();
        assertThat(code(change(token, PASSWORD, PASSWORD, 400))).isEqualTo("VALIDATION_ERROR");
        call(get("/api/me"), token, 200);
    }

    @Test
    void 한글은_72바이트_기준_24자까지() throws Exception {
        String token = newDisposableUserToken();
        String korean24 = "가".repeat(24); // 72바이트
        String korean25 = "가".repeat(25); // 75바이트, 글자 수는 72 이하
        assertThat(code(change(token, PASSWORD, korean25, 400))).isEqualTo("VALIDATION_ERROR");
        String fresh = change(token, PASSWORD, korean24, 200).get("accessToken").asText();
        String email = jdbcEmail(fresh);
        login(email, korean24, 200);
        // 다시 같은 값으로 바꾸려 하면 같은 비밀번호로 거절
        assertThat(code(change(fresh, korean24, korean24, 400))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 가입은_72바이트_초과_한글_비밀번호를_400_VALIDATION_ERROR로_거절한다() throws Exception {
        String email = randomEmail();
        try {
            JsonNode err = objectMapper.readTree(mvc.perform(jsonPost("/api/auth/signup",
                            Map.of("email", email, "password", "가".repeat(25))))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
            assertThat(code(err)).isEqualTo("VALIDATION_ERROR");
            Long rows = jdbc.sql("select count(*) from users where email = :e").param("e", email)
                    .query(Long.class).single();
            assertThat(rows).isZero();
            // 24자(72바이트)는 가입되고 같은 비밀번호로 로그인된다
            signup(email, "가".repeat(24));
            login(email, "가".repeat(24), 200);
            // 로그인에 72바이트 초과를 보내면 500 이 아니라 401(불일치)
            login(email, "가".repeat(25), 401);
            login(randomEmail(), "가".repeat(25), 401);
        } finally {
            jdbc.sql("delete from users where email = :e").param("e", email).update();
        }
    }

    @Test
    void 비밀번호_변경_후_토큰이_발급된_버전을_가진다() throws Exception {
        String token = newDisposableUserToken();
        var userId = userIdOf(token);
        assertThat(jwtService.parse(token).orElseThrow().version()).isZero();
        String fresh = change(token, PASSWORD, NEW_PASSWORD, 200).get("accessToken").asText();
        assertThat(jwtService.parse(fresh).orElseThrow().version()).isEqualTo(1);
        Integer db = jdbc.sql("select token_version from users where id = :id").param("id", userId)
                .query(Integer.class).single();
        assertThat(db).isEqualTo(1);
    }

    @Test
    void 미인증은_401() throws Exception {
        mvc.perform(put("/api/me/password").contentType("application/json")
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"yyyyyyyy\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer garbage")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"yyyyyyyy\"}"))
                .andExpect(status().isUnauthorized());
    }
}
