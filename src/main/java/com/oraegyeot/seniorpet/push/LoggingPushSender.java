package com.oraegyeot.seniorpet.push;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 로그 발송기(FCM 비활성 기본값). 실제로 보내지 않고 로그만 남기고, 모든 토큰을 성공으로 돌려준다.
 * 토큰은 앞 8자만 남기고 가린다.
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public List<PushResult> send(List<String> tokens, PushMessage message) {
        log.info("[로그 발송기] 푸시 {}건 (실제 발송 없음) title={}, body={}, data={}, tokens={}",
                tokens.size(), message.title(), message.body(), message.data(),
                tokens.stream().map(LoggingPushSender::mask).toList());
        return tokens.stream().map(PushResult::success).toList();
    }

    /** 토큰 앞 8자만 남긴다. 로그에 토큰 전체를 남기지 않는다. */
    static String mask(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 8 ? "****" : token.substring(0, 8) + "…";
    }
}
