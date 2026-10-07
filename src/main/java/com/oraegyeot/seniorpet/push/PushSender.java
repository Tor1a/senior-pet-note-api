package com.oraegyeot.seniorpet.push;

import java.util.List;

/**
 * 푸시 발송기. 구현은 설정으로 고른다(PushConfig).
 * - FcmPushSender: app.push.fcm.enabled=true (운영)
 * - LoggingPushSender: 기본값. 실제로 보내지 않고 로그만 남긴다(로컬 개발)
 * - 테스트는 FakePushSender(test 소스)를 @Primary 로 쓴다. 실제 FCM 호출 없음
 */
public interface PushSender {

    /**
     * 한 메시지를 여러 기기 토큰에 보낸다. 결과는 토큰마다 1개씩, tokens 와 같은 순서로 돌려준다.
     * 호출하는 쪽은 DB 트랜잭션 밖에서 부른다(네트워크 호출).
     */
    List<PushResult> send(List<String> tokens, PushMessage message);
}
