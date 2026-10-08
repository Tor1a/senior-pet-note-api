package com.oraegyeot.seniorpet.account;

import com.oraegyeot.seniorpet.common.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 확인 실패 제한(사용자별, 인메모리). 창(기본 15분) 안에 실패가 한도(기본 5회)에 이르면
 * 가장 오래된 실패가 창을 벗어날 때까지 429. 성공하면 초기화한다.
 * 한계: 서버를 재시작하거나 인스턴스가 여러 개면 카운터가 따로 논다(MVP 단일 인스턴스 전제).
 * 시각은 주입된 Clock 으로만 구한다.
 */
@Component
public class PasswordAttemptLimiter {

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final ConcurrentHashMap<UUID, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public PasswordAttemptLimiter(Clock clock, AccountProperties props) {
        this.clock = clock;
        this.maxFailures = props.maxFailuresOrDefault();
        this.window = props.failureWindowOrDefault();
    }

    /**
     * 비밀번호 비교 전에 부른다. 한도에 이르렀으면 429(TOO_MANY_ATTEMPTS), 아니면 시도 1회를 먼저 센다(선증가).
     * 확인과 기록이 한 락 안에서 일어나므로 동시 요청이 몰려도 비교 횟수가 한도를 넘지 않는다.
     * 비밀번호가 맞으면 호출한 쪽이 reset 으로 초기화한다(그때까지는 실패로 센 상태).
     */
    public void acquire(UUID userId) {
        Instant now = clock.instant();
        long[] retryAfter = {0};
        failures.compute(userId, (id, q) -> {
            Deque<Instant> deque = q != null ? q : new ArrayDeque<>();
            synchronized (deque) {
                prune(deque, now);
                if (deque.size() >= maxFailures) {
                    Duration wait = Duration.between(now, deque.peekFirst().plus(window));
                    retryAfter[0] = Math.max(1, (wait.toMillis() + 999) / 1000);
                } else {
                    deque.addLast(now);
                }
            }
            return deque.isEmpty() ? null : deque;
        });
        if (retryAfter[0] > 0) {
            throw ApiException.tooManyAttempts(retryAfter[0]);
        }
    }

    public void reset(UUID userId) {
        failures.remove(userId);
    }

    private void prune(Deque<Instant> q, Instant now) {
        Instant limit = now.minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(limit)) {
            q.removeFirst();
        }
    }
}
