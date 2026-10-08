package com.oraegyeot.seniorpet.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.account.* 설정. 비밀번호 확인 실패 한도(기본 15분 안에 5회). */
@ConfigurationProperties("app.account")
public record AccountProperties(Integer maxFailures, Duration failureWindow) {

    int maxFailuresOrDefault() {
        return maxFailures != null && maxFailures > 0 ? maxFailures : 5;
    }

    Duration failureWindowOrDefault() {
        return failureWindow != null && !failureWindow.isZero() && !failureWindow.isNegative()
                ? failureWindow : Duration.ofMinutes(15);
    }
}
