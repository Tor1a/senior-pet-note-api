package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 투약 알림 설정 API(GET/PUT /api/medications/{id}/reminder).
 * 시계: 2026-10-06(화) 09:00 서울. 약 시각 08:00, 20:00.
 */
class ReminderApiTest extends ApiTestSupport {

    private String token;
    private String med;

    @BeforeEach
    void setUp() throws Exception {
        setSeoulTime(2026, 10, 6, 9, 0);
        token = newDisposableUserToken();
        med = createMedication(token, createPet(token), List.of("20:00", "08:00"));
    }

    private String url() {
        return "/api/medications/" + med + "/reminder";
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void assertBadRequest(Map<String, Object> body) throws Exception {
        JsonNode err = call(jsonPut(url(), body), token, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 설정_전_GET은_기본값() throws Exception {
        JsonNode r = call(get(url()), token, 200);
        assertThat(r.get("medicationId").asText()).isEqualTo(med);
        assertThat(r.get("enabled").asBoolean()).isFalse();
        assertThat(r.get("repeat").asText()).isEqualTo("daily");
        assertThat(r.get("daysOfWeek")).isEmpty();
        assertThat(r.get("intervalDays").isNull()).isTrue();
        assertThat(r.get("startDate").asText()).isEqualTo("2026-10-06");
        assertThat(r.get("endDate").isNull()).isTrue();
        assertThat(r.get("times").toString()).isEqualTo("[\"08:00\",\"20:00\"]");
        assertThat(r.get("nextFireAt").isNull()).isTrue();
        assertThat(r.get("updatedAt").isNull()).isTrue();
    }

    @Test
    void 기본값의_시작일은_새벽4시_규칙을_따른다() throws Exception {
        setSeoulTime(2026, 10, 7, 3, 59);
        assertThat(call(get(url()), token, 200).get("startDate").asText()).isEqualTo("2026-10-06");
    }

    @Test
    void weekly_설정은_요일을_정렬하고_다음_발송_시각을_계산한다() throws Exception {
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("wed", "mon")));
        assertThat(r.get("enabled").asBoolean()).isTrue();
        assertThat(r.get("repeat").asText()).isEqualTo("weekly");
        assertThat(r.get("daysOfWeek").toString()).isEqualTo("[\"mon\",\"wed\"]");
        assertThat(r.get("startDate").asText()).isEqualTo("2026-10-06"); // 생략 → 현재 기록 날짜
        // 화요일 09:00 → 다음 = 수요일 08:00(서울) = 2026-10-06T23:00Z
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-06T23:00:00Z");
        assertThat(r.get("updatedAt").isNull()).isFalse();
        // GET 도 같은 값
        JsonNode got = call(get(url()), token, 200);
        assertThat(got.get("daysOfWeek").toString()).isEqualTo("[\"mon\",\"wed\"]");
        assertThat(got.get("nextFireAt").asText()).isEqualTo("2026-10-06T23:00:00Z");
    }

    @Test
    void daily_설정의_다음_발송은_오늘_20시() throws Exception {
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "daily"));
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-06T11:00:00Z");
    }

    @Test
    void 두번_PUT하면_전체_교체되고_행은_1개() throws Exception {
        putReminder(token, med, body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon"),
                "endDate", "2026-12-31"));
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "interval", "intervalDays", 3,
                "startDate", "2026-10-05"));
        assertThat(r.get("repeat").asText()).isEqualTo("interval");
        assertThat(r.get("daysOfWeek")).isEmpty();
        assertThat(r.get("intervalDays").asInt()).isEqualTo(3);
        assertThat(r.get("startDate").asText()).isEqualTo("2026-10-05");
        assertThat(r.get("endDate").isNull()).isTrue(); // 보내지 않은 값은 지워진다(전체 교체)
        // 10-05 기준 3일 간격 → 10-08 08:00(서울) = 2026-10-07T23:00Z
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-07T23:00:00Z");
        Long rows = jdbc.sql("select count(*) from medication_reminders where medication_id = :id")
                .param("id", UUID.fromString(med)).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void 꺼진_규칙과_종료된_규칙은_nextFireAt이_null() throws Exception {
        assertThat(putReminder(token, med, body("enabled", false, "repeat", "daily")).get("nextFireAt").isNull()).isTrue();
        JsonNode ended = putReminder(token, med, body("enabled", true, "repeat", "daily",
                "startDate", "2026-10-01", "endDate", "2026-10-05"));
        assertThat(ended.get("nextFireAt").isNull()).isTrue();
    }

    @Test
    void 약_시각을_바꾸면_알림_시각도_따라간다() throws Exception {
        putReminder(token, med, body("enabled", true, "repeat", "daily"));
        call(jsonPut("/api/medications/" + med, Map.of("name", "아조딜", "times", List.of("21:30"))), token, 200);
        JsonNode r = call(get(url()), token, 200);
        assertThat(r.get("times").toString()).isEqualTo("[\"21:30\"]");
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-06T12:30:00Z");
    }

    @Test
    void 반복_방식이_아닌_필드에_빈_배열은_허용() throws Exception {
        // GET 응답(daysOfWeek: [])을 그대로 PUT 해도 되도록 빈 배열은 "보내지 않음"과 같게 본다
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "daily", "daysOfWeek", List.of()));
        assertThat(r.get("daysOfWeek")).isEmpty();
    }

    @Test
    void 검증_규칙_위반은_모두_400() throws Exception {
        assertBadRequest(body("enabled", true));                                            // repeat 없음
        assertBadRequest(body("enabled", true, "repeat", "monthly"));                       // 알 수 없는 repeat
        assertBadRequest(body("repeat", "daily"));                                          // enabled 없음
        assertBadRequest(body("enabled", true, "repeat", "weekly"));                        // 요일 없음
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of()));
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon", "mon")));
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("monday")));
        assertBadRequest(body("enabled", true, "repeat", "daily", "daysOfWeek", List.of("mon")));    // daily 에 요일
        assertBadRequest(body("enabled", true, "repeat", "interval"));                      // 간격 없음
        assertBadRequest(body("enabled", true, "repeat", "interval", "intervalDays", 1));
        assertBadRequest(body("enabled", true, "repeat", "interval", "intervalDays", 31));
        assertBadRequest(body("enabled", true, "repeat", "daily", "intervalDays", 3));      // daily 에 간격
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon"), "intervalDays", 3));
        assertBadRequest(body("enabled", true, "repeat", "daily", "startDate", "2026-10-10", "endDate", "2026-10-09"));
        assertBadRequest(body("enabled", true, "repeat", "daily", "endDate", "2026-10-05")); // 생략된 시작일(10-06)보다 앞
        assertBadRequest(body("enabled", true, "repeat", "daily", "startDate", "2026-13-01")); // 날짜 형식
        // 실패한 PUT 은 아무것도 저장하지 않는다
        assertThat(call(get(url()), token, 200).get("updatedAt").isNull()).isTrue();
    }

    @Test
    void 과거_시작일은_허용() throws Exception {
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "daily", "startDate", "2025-01-01"));
        assertThat(r.get("startDate").asText()).isEqualTo("2025-01-01");
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-06T11:00:00Z");
    }

    @Test
    void 비활성_약과_없는_약은_404() throws Exception {
        call(delete("/api/medications/" + med), token, 204);
        assertThat(call(get(url()), token, 404).get("code").asText()).isEqualTo("NOT_FOUND");
        call(jsonPut(url(), body("enabled", true, "repeat", "daily")), token, 404);
        call(get("/api/medications/" + UUID.randomUUID() + "/reminder"), token, 404);
        call(get("/api/medications/not-a-uuid/reminder"), token, 404);
    }
}
