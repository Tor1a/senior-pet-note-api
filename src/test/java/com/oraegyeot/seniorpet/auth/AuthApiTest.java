package com.oraegyeot.seniorpet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 인증 API 계약 테스트: signup → login → /api/me, 401, 409, 400. */
class AuthApiTest extends ApiTestSupport {

    @Test
    void 헬스체크는_인증없이_200() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void 회원가입_로그인_내정보_흐름() throws Exception {
        String email = randomEmail();

        // 1) 회원가입 → 201 {accessToken, user:{id, email}}
        JsonNode signup = signup(email, PASSWORD);
        assertThat(signup.get("accessToken").asText()).isNotBlank();
        String userId = signup.get("user").get("id").asText();
        assertThat(signup.get("user").get("email").asText()).isEqualTo(email);

        // 2) 로그인 → 200 {accessToken, user:{id, email}} (이메일 대소문자 무시)
        String loginBody = mvc.perform(jsonPost("/api/auth/login",
                        Map.of("email", email.toUpperCase(), "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginBody).get("accessToken").asText();

        // 3) 내 정보 → 200 {id, email}
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void 토큰없이_내정보는_401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void 잘못된_토큰은_401() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 다른_키로_서명한_토큰은_401() throws Exception {
        String forged = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(
                        "attacker-secret-key-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.UTF_8)))
                .compact();
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 중복_이메일은_409() throws Exception {
        String email = randomEmail();
        signup(email, PASSWORD);
        mvc.perform(jsonPost("/api/auth/signup", Map.of("email", email, "password", "another-pass")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void 짧은_비밀번호는_400() throws Exception {
        mvc.perform(jsonPost("/api/auth/signup", Map.of("email", randomEmail(), "password", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 잘못된_이메일_형식은_400() throws Exception {
        mvc.perform(jsonPost("/api/auth/signup", Map.of("email", "not-an-email", "password", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 틀린_비밀번호와_없는_이메일은_모두_401() throws Exception {
        String email = randomEmail();
        signup(email, PASSWORD);
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", email, "password", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(jsonPost("/api/auth/login", Map.of("email", randomEmail(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void CORS_허용_출처는_localhost_5173과_4173() throws Exception {
        for (String origin : new String[] {"http://localhost:5173", "http://localhost:4173"}) {
            // 보호된 API(/api/me)의 preflight 도 토큰 없이 통과해야 한다
            mvc.perform(options("/api/me")
                            .header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
        }
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
