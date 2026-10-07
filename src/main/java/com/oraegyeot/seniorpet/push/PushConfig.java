package com.oraegyeot.seniorpet.push;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 푸시 발송기 선택. app.push.fcm.enabled(환경변수 FCM_ENABLED) 하나로 정한다.
 * - true  → FcmPushSender. 자격증명이 없거나 해석할 수 없으면 서버가 시작되지 않는다(JWT_SECRET 과 같은 방식)
 * - false·미설정 → LoggingPushSender(실제 발송 없음)
 */
@Configuration
public class PushConfig {

    @Bean
    @ConditionalOnProperty(name = "app.push.fcm.enabled", havingValue = "true")
    public PushSender fcmPushSender(FcmProperties props, Clock clock) {
        return new FcmPushSender(props, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "app.push.fcm.enabled", havingValue = "false", matchIfMissing = true)
    public PushSender loggingPushSender() {
        LoggerFactory.getLogger(PushConfig.class).warn("FCM_ENABLED=false: 로그 발송기를 사용합니다(실제 발송 없음).");
        return new LoggingPushSender();
    }
}
