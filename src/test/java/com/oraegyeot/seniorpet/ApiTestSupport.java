package com.oraegyeot.seniorpet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraegyeot.seniorpet.push.FakePushSender;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API 통합 테스트 공통 부모.
 * 실제 PostgreSQL(seniorpet_test DB)에 붙는다 → README.md "테스트" 참고.
 * 테스트끼리 데이터가 섞이지 않도록 매번 무작위 이메일로 새 사용자를 만든다.
 * 시각은 MutableClock(@Primary Clock)으로 고정할 수 있고, 테스트가 끝나면 실제 시각으로 돌린다.
 * 푸시는 FakePushSender(@Primary PushSender)로 받는다(실제 FCM 호출 없음). 테스트마다 기록을 비운다.
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

        @Bean
        @Primary
        FakePushSender fakePushSender() {
            return new FakePushSender();
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected FakePushSender pushSender;

    @Autowired
    protected JdbcClient jdbc;

    /** newDisposableUserToken() 으로 만든 사용자. 테스트가 끝나면 삭제한다(데이터는 on delete cascade). */
    private final List<UUID> disposableUsers = new ArrayList<>();

    @AfterEach
    void resetClock() {
        clock.reset();
        pushSender.reset();
        for (UUID id : disposableUsers) {
            jdbc.sql("delete from users where id = :id").param("id", id).update();
        }
        disposableUsers.clear();
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

    /**
     * 테스트가 끝나면 삭제되는 사용자를 만든다. 알림 발송 작업은 전 사용자의 규칙을 훑으므로
     * 알림 규칙을 만드는 테스트는 이걸 써서 다음 실행에 데이터가 쌓이지 않게 한다.
     */
    protected String newDisposableUserToken() throws Exception {
        String token = newUserToken();
        disposableUsers.add(userIdOf(token));
        return token;
    }

    /** 이미 만든 사용자를 테스트 끝에 삭제하도록 등록한다(이미 지워졌으면 0행이라 무해). */
    protected void registerForCleanup(UUID userId) {
        disposableUsers.add(userId);
    }

    /** GET /api/me 로 사용자 id 를 얻는다. */
    protected UUID userIdOf(String token) throws Exception {
        return UUID.fromString(call(get("/api/me"), token, 200).get("id").asText());
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

    /** 알림 규칙을 설정(PUT)하고 응답 JSON 을 돌려준다. */
    protected JsonNode putReminder(String token, String medicationId, Map<String, ?> body) throws Exception {
        return call(jsonPut("/api/medications/" + medicationId + "/reminder", body), token, 200);
    }

    /** 기기 토큰을 등록하고 기기 id 를 돌려준다. */
    protected String registerDevice(String token, String deviceToken, String platform) throws Exception {
        return call(jsonPut("/api/devices", Map.of("token", deviceToken, "platform", platform)), token, 200)
                .get("id").asText();
    }
}
