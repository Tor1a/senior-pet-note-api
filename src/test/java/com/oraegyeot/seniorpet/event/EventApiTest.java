package com.oraegyeot.seniorpet.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 지표 이벤트 API 테스트 (docs/api-today.md 6절). */
class EventApiTest extends ApiTestSupport {

    @Test
    void 허용된_이름은_202_본문없음() throws Exception {
        String t = newUserToken();
        assertThat(call(jsonPost("/api/events", Map.of("name", "today_opened", "props", Map.of("source", "push"))),
                t, 202)).isNull();
        call(jsonPost("/api/events", Map.of("name", "med_checked")), t, 202);
        call(jsonPost("/api/events", Map.of("name", "daily_log_saved",
                "props", Map.of("taps", 4, "durationMs", 9100))), t, 202);
    }

    @Test
    void 허용_이름_외는_400() throws Exception {
        String t = newUserToken();
        assertThat(call(jsonPost("/api/events", Map.of("name", "signup")), t, 400).get("code").asText())
                .isEqualTo("VALIDATION_ERROR");
        call(jsonPost("/api/events", Map.of("name", "")), t, 400);
        call(jsonPost("/api/events", Map.of("props", Map.of())), t, 400);
        // props 가 객체가 아니면 400
        call(post("/api/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"today_opened\",\"props\":[1,2]}"), t, 400);
        // 너무 큰 props
        call(jsonPost("/api/events", Map.of("name", "today_opened", "props", Map.of("x", "a".repeat(2000)))), t, 400);
    }

    @Test
    void 로그인없이는_401() throws Exception {
        mvc.perform(jsonPost("/api/events", Map.of("name", "today_opened"))).andExpect(status().isUnauthorized());
    }
}
