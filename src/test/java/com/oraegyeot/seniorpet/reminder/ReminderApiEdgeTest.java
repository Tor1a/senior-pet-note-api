package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 투약 알림 설정 API 경계·오류 케이스(QA 보강). ReminderApiTest 에 없는 것만 다룬다.
 * 시계: 2026-10-06(화) 09:00 서울. 약 시각 08:00, 20:00.
 */
class ReminderApiEdgeTest extends ApiTestSupport {

    private String token;
    private String med;

    @BeforeEach
    void setUp() throws Exception {
        setSeoulTime(2026, 10, 6, 9, 0);
        token = newDisposableUserToken();
        med = createMedication(token, createPet(token), List.of("08:00", "20:00"));
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
        assertThat(err.get("code").asText()).as(body.toString()).isEqualTo("VALIDATION_ERROR");
        assertThat(err.get("message").asText()).isNotBlank();
    }

    private void assertRawBadRequest(String rawJson) throws Exception {
        JsonNode err = call(put(url()).contentType(MediaType.APPLICATION_JSON).content(rawJson), token, 400);
        assertThat(err.get("code").asText()).as(rawJson).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void interval_경계값_2와_30은_허용() throws Exception {
        assertThat(putReminder(token, med, body("enabled", true, "repeat", "interval", "intervalDays", 2))
                .get("intervalDays").asInt()).isEqualTo(2);
        assertThat(putReminder(token, med, body("enabled", true, "repeat", "interval", "intervalDays", 30))
                .get("intervalDays").asInt()).isEqualTo(30);
    }

    @Test
    void 종료일이_시작일과_같으면_허용() throws Exception {
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "daily",
                "startDate", "2026-10-06", "endDate", "2026-10-06"));
        assertThat(r.get("endDate").asText()).isEqualTo("2026-10-06");
        assertThat(r.get("nextFireAt").asText()).isEqualTo("2026-10-06T11:00:00Z"); // 오늘 20:00
    }

    @Test
    void weekly_7개_요일_모두는_허용하고_월부터_정렬() throws Exception {
        JsonNode r = putReminder(token, med, body("enabled", true, "repeat", "weekly",
                "daysOfWeek", List.of("sun", "sat", "fri", "thu", "wed", "tue", "mon")));
        assertThat(r.get("daysOfWeek").toString())
                .isEqualTo("[\"mon\",\"tue\",\"wed\",\"thu\",\"fri\",\"sat\",\"sun\"]");
    }

    @Test
    void interval에_빈_요일_배열과_null은_허용() throws Exception {
        Map<String, Object> b = body("enabled", true, "repeat", "interval", "intervalDays", 3, "daysOfWeek", List.of());
        assertThat(putReminder(token, med, b).get("repeat").asText()).isEqualTo("interval");
        Map<String, Object> withNulls = body("enabled", true, "repeat", "daily");
        withNulls.put("daysOfWeek", null);
        withNulls.put("intervalDays", null);
        withNulls.put("startDate", null);
        withNulls.put("endDate", null);
        JsonNode r = putReminder(token, med, withNulls);
        assertThat(r.get("startDate").asText()).isEqualTo("2026-10-06");
    }

    @Test
    void 추가_검증_위반은_모두_400() throws Exception {
        assertBadRequest(body("enabled", true, "repeat", "DAILY"));                                  // 대문자
        assertBadRequest(body("enabled", true, "repeat", ""));                                       // 빈 문자열
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("MON")));   // 대문자 요일
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("")));
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek",
                Arrays.asList("mon", null)));                                                         // null 요일
        assertBadRequest(body("enabled", true, "repeat", "weekly", "daysOfWeek",
                List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun", "mon")));                    // 8개
        assertBadRequest(body("enabled", true, "repeat", "interval", "intervalDays", 0));
        assertBadRequest(body("enabled", true, "repeat", "interval", "intervalDays", -3));
        assertBadRequest(body("enabled", true, "repeat", "interval", "intervalDays", 3,
                "daysOfWeek", List.of("mon")));                                                       // interval 에 요일
        assertBadRequest(body("enabled", true, "repeat", "daily", "startDate", "2026-02-30"));     // 없는 날짜
        assertBadRequest(body("enabled", true, "repeat", "daily", "endDate", "10/06/2026"));       // 형식
        Map<String, Object> nullEnabled = body("repeat", "daily");
        nullEnabled.put("enabled", null);
        assertBadRequest(nullEnabled);
        assertThat(call(get(url()), token, 200).get("updatedAt").isNull()).isTrue();
    }

    @Test
    void 잘못된_JSON과_타입은_400() throws Exception {
        assertRawBadRequest("{\"enabled\": true, \"repeat\": \"daily\""); // 닫는 괄호 없음
        assertRawBadRequest("");                                          // 빈 본문
        assertRawBadRequest("{\"enabled\": \"yes\", \"repeat\": \"daily\"}");
        assertRawBadRequest("{\"enabled\": true, \"repeat\": \"interval\", \"intervalDays\": \"abc\"}");
        assertRawBadRequest("{\"enabled\": true, \"repeat\": \"weekly\", \"daysOfWeek\": \"mon\"}"); // 배열 아님
        assertRawBadRequest("[]");
    }

    @Test
    void 실패한_PUT은_이전_설정을_바꾸지_않는다() throws Exception {
        putReminder(token, med, body("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon")));
        assertBadRequest(body("enabled", false, "repeat", "interval", "intervalDays", 99));
        JsonNode r = call(get(url()), token, 200);
        assertThat(r.get("enabled").asBoolean()).isTrue();
        assertThat(r.get("repeat").asText()).isEqualTo("weekly");
        assertThat(r.get("daysOfWeek").toString()).isEqualTo("[\"mon\"]");
    }

    @Test
    void 로그인_없이는_401() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        mvc.perform(jsonPut(url(), body("enabled", true, "repeat", "daily"))).andExpect(status().isUnauthorized());
        mvc.perform(get(url()).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_사용자의_약_id_로_PUT해도_그_약에_규칙이_생기지_않는다() throws Exception {
        String bob = newDisposableUserToken();
        call(jsonPut(url(), body("enabled", true, "repeat", "daily")), bob, 404);
        Long rows = jdbc.sql("select count(*) from medication_reminders where medication_id = :id")
                .param("id", UUID.fromString(med)).query(Long.class).single();
        assertThat(rows).isZero();
        // 소유자 본인에게는 여전히 기본값
        assertThat(call(get(url()), token, 200).get("updatedAt").isNull()).isTrue();
    }

    @Test
    void 첫_설정이_동시에_들어와도_모두_200이고_행은_1개() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    int s = mvc.perform(jsonPut(url(), body("enabled", true, "repeat", "interval",
                                    "intervalDays", 2 + n))
                                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                            .andReturn().getResponse().getStatus();
                    statuses.add(s);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> fu : futures) {
                fu.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(statuses).hasSize(threads).containsOnly(200);
        Long rows = jdbc.sql("select count(*) from medication_reminders where medication_id = :id")
                .param("id", UUID.fromString(med)).query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }
}
