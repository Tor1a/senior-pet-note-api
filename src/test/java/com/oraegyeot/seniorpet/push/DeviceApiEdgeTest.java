package com.oraegyeot.seniorpet.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 기기 토큰 API 경계·오류·동시성(QA 보강). DeviceApiTest 에 없는 것만 다룬다. */
class DeviceApiEdgeTest extends ApiTestSupport {

    private static String newFcmToken() {
        return "fcm-" + UUID.randomUUID();
    }

    private long countByToken(String fcmToken) {
        return jdbc.sql("select count(*) from device_tokens where token = :t").param("t", fcmToken)
                .query(Long.class).single();
    }

    private UUID ownerOf(String fcmToken) {
        return jdbc.sql("select user_id from device_tokens where token = :t").param("t", fcmToken)
                .query(UUID.class).single();
    }

    private void assertBadRequest(String token, Map<String, Object> body) throws Exception {
        assertThat(call(jsonPut("/api/devices", body), token, 400).get("code").asText())
                .as(body.toString()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 추가_검증_위반은_400() throws Exception {
        String token = newDisposableUserToken();
        assertBadRequest(token, Map.of("token", newFcmToken(), "platform", "Android")); // 대문자
        assertBadRequest(token, Map.of("token", newFcmToken(), "platform", ""));
        assertBadRequest(token, Map.of("token", "line\nbreak", "platform", "web"));
        assertBadRequest(token, Map.of("token", " leading", "platform", "web"));
        assertBadRequest(token, Map.of("token", "trailing ", "platform", "web"));
        assertBadRequest(token, Map.of("token", "   ", "platform", "web"));
        JsonNode err = call(put("/api/devices").contentType(MediaType.APPLICATION_JSON).content("{\"token\":"),
                token, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        err = call(put("/api/devices").contentType(MediaType.APPLICATION_JSON).content("{\"token\":123,\"platform\":[]}"),
                token, 400);
        assertThat(err.get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 한_글자_토큰은_허용() throws Exception {
        String token = newDisposableUserToken();
        // 전역 unique 이므로 다른 테스트와 겹치지 않는 1자(사용자 삭제 시 함께 지워짐)
        String one = "é"; // é
        call(jsonPut("/api/devices", Map.of("token", one, "platform", "web")), token, 200);
        assertThat(ownerOf(one)).isEqualTo(userIdOf(token));
    }

    @Test
    void 유니코드_공백이_든_토큰은_400이어야_한다() throws Exception {
        // 계약: token 공백 불가. 정규식 \S 는 기본 모드에서 ASCII 공백만 막는다.
        String token = newDisposableUserToken();
        assertBadRequest(token, Map.of("token", "abc　def-" + UUID.randomUUID(), "platform", "web")); // 전각 공백
        assertBadRequest(token, Map.of("token", "abc def-" + UUID.randomUUID(), "platform", "web")); // NBSP
    }

    @Test
    void 잘못된_id_형식_삭제는_404() throws Exception {
        String token = newDisposableUserToken();
        call(delete("/api/devices/not-a-uuid"), token, 404);
    }

    @Test
    void 로그인_없이_삭제는_401() throws Exception {
        mvc.perform(delete("/api/devices/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void 토큰이_이전된_뒤_이전_사용자가_재등록하면_다시_되돌아온다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String fcm = newFcmToken();
        registerDevice(alice, fcm, "android");
        registerDevice(bob, fcm, "android");
        assertThat(ownerOf(fcm)).isEqualTo(userIdOf(bob));
        String back = registerDevice(alice, fcm, "android");
        assertThat(ownerOf(fcm)).isEqualTo(userIdOf(alice));
        assertThat(countByToken(fcm)).isEqualTo(1);
        call(delete("/api/devices/" + back), alice, 204);
    }

    @Test
    void 이전받은_토큰으로_한도가_차면_받는_사용자의_가장_오래된_토큰을_지운다() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        List<String> bobIds = new ArrayList<>();
        for (int i = 0; i < DeviceTokenService.MAX_TOKENS_PER_USER; i++) {
            setSeoulTime(2026, 10, 6, 9, i);
            bobIds.add(registerDevice(bob, newFcmToken(), "web"));
        }
        String shared = newFcmToken();
        setSeoulTime(2026, 10, 6, 10, 0);
        registerDevice(alice, shared, "ios");
        setSeoulTime(2026, 10, 6, 11, 0);
        registerDevice(bob, shared, "ios");
        assertThat(jdbc.sql("select count(*) from device_tokens where user_id = :u").param("u", userIdOf(bob))
                .query(Long.class).single()).isEqualTo(10L);
        assertThat(jdbc.sql("select count(*) from device_tokens where user_id = :u").param("u", userIdOf(alice))
                .query(Long.class).single()).isZero();
        call(delete("/api/devices/" + bobIds.get(0)), bob, 404);
    }

    @Test
    void 같은_사용자가_같은_토큰을_동시에_등록해도_모두_200이고_행은_1개() throws Exception {
        String token = newDisposableUserToken();
        String fcm = newFcmToken();
        List<Integer> statuses = runConcurrently(6, i -> token, fcm);
        assertThat(statuses).containsOnly(200);
        assertThat(countByToken(fcm)).isEqualTo(1);
    }

    @Test
    void 두_사용자가_같은_토큰을_동시에_등록해도_모두_200이고_행은_1개() throws Exception {
        String alice = newDisposableUserToken();
        String bob = newDisposableUserToken();
        String fcm = newFcmToken();
        List<Integer> statuses = runConcurrently(6, i -> i % 2 == 0 ? alice : bob, fcm);
        assertThat(statuses).containsOnly(200);
        assertThat(countByToken(fcm)).isEqualTo(1);
        assertThat(ownerOf(fcm)).isIn(userIdOf(alice), userIdOf(bob));
    }

    private List<Integer> runConcurrently(int threads, java.util.function.IntFunction<String> tokenOf, String fcm)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String jwt = tokenOf.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    statuses.add(mvc.perform(jsonPut("/api/devices", Map.of("token", fcm, "platform", "android"))
                                    .header(HttpHeaders.AUTHORIZATION, bearer(jwt)))
                            .andReturn().getResponse().getStatus());
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
        return statuses;
    }
}
