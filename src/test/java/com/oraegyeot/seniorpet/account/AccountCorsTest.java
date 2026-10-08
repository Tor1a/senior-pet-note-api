package com.oraegyeot.seniorpet.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 브라우저가 429 의 Retry-After 를 읽으려면 Access-Control-Expose-Headers 가 필요하다. */
class AccountCorsTest extends ApiTestSupport {

    private static final String ORIGIN = "http://localhost:5173";

    @Test
    void 실요청_429_응답에_Retry_After_노출_헤더가_실린다() throws Exception {
        String token = newDisposableUserToken();
        for (int i = 0; i < 5; i++) {
            call(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass", "confirm", true)), token, 400);
        }
        mvc.perform(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass", "confirm", true))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Retry-After"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void preflight_는_통과하고_허용_메서드에_PUT_이_있다() throws Exception {
        mvc.perform(options("/api/me/password")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }
}
