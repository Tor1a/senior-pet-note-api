package com.oraegyeot.seniorpet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API 통합 테스트 공통 부모.
 * 실제 PostgreSQL(seniorpet_test DB)에 붙는다 → backend/README.md "테스트" 참고.
 * 테스트끼리 데이터가 섞이지 않도록 매번 무작위 이메일로 새 사용자를 만든다.
 * 시각은 MutableClock(@Primary Clock)으로 고정할 수 있고, 테스트가 끝나면 실제 시각으로 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiTestSupport.TestClockConfig.class)
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "password123";
    protected static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** 서울 현지 시각으로 시계를 고정한다. 예: setSeoulTime(2026, 10, 6, 3, 59) */
    protected void setSeoulTime(int y, int mo, int d, int h, int mi) {
        clock.set(LocalDateTime.of(y, mo, d, h, mi).atZone(SEOUL).toInstant());
    }

    protected static String randomEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder jsonPut(String url, Object body) throws Exception {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    /** 회원가입 후 응답 JSON 을 돌려준다. */
    protected JsonNode signup(String email, String password) throws Exception {
        String body = mvc.perform(jsonPost("/api/auth/signup", Map.of("email", email, "password", password)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** 새 사용자를 만들고 accessToken 을 돌려준다. */
    protected String newUserToken() throws Exception {
        return signup(randomEmail(), PASSWORD).get("accessToken").asText();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 요청 → 기대 상태 확인 → 응답 JSON */
    protected JsonNode call(MockHttpServletRequestBuilder req, String token, int expectedStatus) throws Exception {
        String body = mvc.perform(req.header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : objectMapper.readTree(body);
    }

    /** 반려동물을 만들고 id 를 돌려준다. */
    protected String createPet(String token) throws Exception {
        return call(jsonPost("/api/pets", Map.of("name", "초코", "species", "dog")), token, 201)
                .get("id").asText();
    }

    /** 약을 등록하고 id 를 돌려준다. */
    protected String createMedication(String token, String petId, List<String> times) throws Exception {
        return call(jsonPost("/api/pets/" + petId + "/medications",
                Map.of("name", "아조딜", "doseText", "1캡슐", "times", times)), token, 201)
                .get("id").asText();
    }
}
