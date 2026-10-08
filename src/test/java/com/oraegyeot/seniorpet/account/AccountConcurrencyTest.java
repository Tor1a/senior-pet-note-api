package com.oraegyeot.seniorpet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** 동시 요청: 속도 제한 우회 방지, 비밀번호 변경 경합. */
class AccountConcurrencyTest extends ApiTestSupport {

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @Autowired
    private UserRepository userRepository;

    private <T> List<T> runTogether(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(jobs.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> job : jobs) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return job.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 틀린_비밀번호_20개를_동시에_보내도_비교는_한도_5회까지만() throws Exception {
        String token = newDisposableUserToken();
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> mvc.perform(jsonPost("/api/me/withdraw", Map.of("password", "wrong-pass", "confirm", true))
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = runTogether(jobs);
        // 400 = 비밀번호 비교가 실제로 일어난 요청
        assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(15);
        // 잠금 중에는 맞는 비밀번호도 429, 계정은 그대로
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), token, 429);
        call(get("/api/me"), token, 200);
    }

    @Test
    void 한도_안에서의_동시_성공은_초기화되고_카운터가_남지_않는다() throws Exception {
        String token = newDisposableUserToken();
        // 맞는 비밀번호 3건 동시 변경: 한도(5) 이내라 429 가 없어야 한다(200/409/400 중 하나)
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String next = "freshPass" + i + "xx";
            jobs.add(() -> mvc.perform(jsonPut("/api/me/password",
                            Map.of("currentPassword", PASSWORD, "newPassword", next))
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(runTogether(jobs)).doesNotContain(429).contains(200);
    }

    @Test
    void 같은_현재_비밀번호의_동시_변경은_정확히_한쪽만_성공하고_성공한_토큰은_유효() throws Exception {
        for (int round = 0; round < 6; round++) {
            String token = newDisposableUserToken();
            List<Callable<String[]>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String next = "concurrentNew" + i + "x";
                jobs.add(() -> {
                    var res = mvc.perform(jsonPut("/api/me/password",
                                    Map.of("currentPassword", PASSWORD, "newPassword", next))
                                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                            .andReturn().getResponse();
                    return new String[] {String.valueOf(res.getStatus()), res.getContentAsString()};
                });
            }
            List<String[]> results = runTogether(jobs);
            List<String[]> ok = results.stream().filter(r -> r[0].equals("200")).toList();
            assertThat(ok).hasSize(1);
            String other = results.stream().filter(r -> !r[0].equals("200")).findFirst().orElseThrow()[0];
            assertThat(other).isIn("409", "400"); // 409 경합 또는 (이미 바뀐 뒤 읽으면) 400 불일치
            String fresh = objectMapper.readTree(ok.get(0)[1]).get("accessToken").asText();
            call(get("/api/me"), fresh, 200); // 성공한 쪽 토큰이 유효
            call(get("/api/me"), token, 401);
            Integer version = jdbc.sql("select token_version from users where id = :id")
                    .param("id", userIdOf(fresh)).query(Integer.class).single();
            assertThat(version).isEqualTo(1);
        }
    }

    @Test
    void 조건부_갱신은_낡은_버전이면_0행() throws Exception {
        String token = newDisposableUserToken();
        var id = userIdOf(token);
        String hash = userRepository.findById(id).orElseThrow().getPasswordHash();
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        assertThat(tx.<Integer>execute(s -> userRepository.changePasswordIfVersion(id, hash, 0))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> userRepository.changePasswordIfVersion(id, hash, 0))).isZero();
        assertThat(userRepository.findTokenVersionById(id)).contains(1);
    }
}
