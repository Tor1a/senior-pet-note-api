package com.oraegyeot.seniorpet.visitquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 보호자가 쓴 글은 오류 응답과 서버 로그에 되돌려 주지 않는다. */
class VisitQuestionPrivacyTest extends ApiTestSupport {

    private static final String SECRET = "비밀질문-" + UUID.randomUUID();

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level previous;
    private Logger appLogger;
    private Logger webLogger;
    private String token;
    private String pet;

    @BeforeEach
    void attach() throws Exception {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previous = root.getLevel();
        // 운영 기본(INFO 이상) 전체 + 우리 패키지·Spring Web 은 DEBUG/TRACE 까지 캡처한다.
        // Hibernate 의 DEBUG/TRACE(엔티티 덤프·바인딩 값)는 운영에서 켜지 않는 설정이라 대상이 아니다.
        root.setLevel(Level.INFO);
        appLogger = (Logger) LoggerFactory.getLogger("com.oraegyeot");
        webLogger = (Logger) LoggerFactory.getLogger("org.springframework.web");
        appLogger.setLevel(Level.TRACE);
        webLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        setSeoulTime(2026, 10, 15, 12, 0);
        token = newDisposableUserToken();
        pet = createPet(token);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
        root.setLevel(previous);
        appLogger.setLevel(null);
        webLogger.setLevel(null);
    }

    private static String stack(IThrowableProxy p) {
        return p == null ? "" : p.getMessage() + stack(p.getCause());
    }

    private void assertNoSecretInLogs() {
        for (ILoggingEvent e : appender.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(SECRET);
            assertThat(stack(e.getThrowableProxy())).doesNotContain(SECRET);
        }
    }

    private void assertNoEcho(JsonNode err) {
        assertThat(err.toString()).doesNotContain(SECRET);
    }

    @Test
    void 검증_오류_응답에_입력을_되돌리지_않는다() throws Exception {
        assertNoEcho(call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", SECRET + "가".repeat(100))),
                token, 400));
        String id = call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", "정상")), token, 201)
                .get("id").asText();
        assertNoEcho(call(jsonPut("/api/visit-questions/" + id, Map.of("text", SECRET + "나".repeat(100))), token, 400));
        // 필수 필드 누락·잘못된 타입·깨진 JSON 에서도 입력이 없다
        Map<String, Object> wrong = new HashMap<>();
        wrong.put("text", null);
        wrong.put("extra", SECRET);
        assertNoEcho(call(jsonPost("/api/pets/" + pet + "/visit-questions", wrong), token, 400));
        assertNoEcho(call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", SECRET)), token, 400));
        assertNoEcho(call(get("/api/pets/" + pet + "/visit-questions?status=" + SECRET), token, 400));
        assertNoEcho(call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", " ", "memo", SECRET)),
                token, 400));
    }

    @Test
    void 한도_오류_응답에_입력을_되돌리지_않는다() throws Exception {
        for (int i = 0; i < 30; i++) {
            call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", "질문 " + i)), token, 201);
        }
        JsonNode err = call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", SECRET)), token, 409);
        assertThat(err.get("code").asText()).isEqualTo("VISIT_QUESTION_LIMIT_REACHED");
        assertNoEcho(err);
    }

    @Test
    void 처리_중_로그에_글이_남지_않는다() throws Exception {
        JsonNode q = call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", SECRET)), token, 201);
        String id = q.get("id").asText();
        call(jsonPut("/api/visit-questions/" + id, Map.of("text", SECRET + "2")), token, 200);
        call(jsonPut("/api/visit-questions/" + id + "/completion", Map.of("completed", true)), token, 200);
        call(get("/api/pets/" + pet + "/visit-questions?status=all"), token, 200);
        call(jsonPost("/api/pets/" + pet + "/visit-questions", Map.of("text", SECRET + "가".repeat(100))), token, 400);
        assertThat(appender.list).isNotEmpty(); // 캡처가 실제로 동작함
        assertNoSecretInLogs();
    }
}
