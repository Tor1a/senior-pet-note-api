package com.oraegyeot.seniorpet.push;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 테스트용 가짜 발송기. ApiTestSupport 가 @Primary PushSender 로 등록한다(실제 FCM 호출 없음).
 * 보낸 메시지를 기록하고, 토큰별로 INVALID_TOKEN / FAILED / 예외를 돌려주게 설정할 수 있다.
 * 테스트 DB 에는 다른 테스트의 데이터가 남아 있으므로 단언은 sentTo(자기 토큰)으로 거른다.
 */
public class FakePushSender implements PushSender {

    /** 발송 1회(멀티캐스트 1건) */
    public record Sent(List<String> tokens, PushMessage message) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Map<String, PushResult.Status> outcomes = new ConcurrentHashMap<>();
    private final Set<String> throwing = ConcurrentHashMap.newKeySet();

    @Override
    public List<PushResult> send(List<String> tokens, PushMessage message) {
        if (tokens.stream().anyMatch(throwing::contains)) {
            throw new IllegalStateException("가짜 발송기 오류(테스트)");
        }
        sent.add(new Sent(List.copyOf(tokens), message));
        return tokens.stream()
                .map(t -> new PushResult(t, outcomes.getOrDefault(t, PushResult.Status.SUCCESS)))
                .toList();
    }

    /** 이 토큰이 포함된 발송 메시지 목록 */
    public List<PushMessage> sentTo(String token) {
        return sent.stream().filter(s -> s.tokens().contains(token)).map(Sent::message).toList();
    }

    /** 이 토큰에 대해 돌려줄 결과 */
    public void willReturn(String token, PushResult.Status status) {
        outcomes.put(token, status);
    }

    /** 이 토큰이 들어간 발송은 예외를 던진다 */
    public void willThrowFor(String token) {
        throwing.add(token);
    }

    public void reset() {
        sent.clear();
        outcomes.clear();
        throwing.clear();
    }
}
