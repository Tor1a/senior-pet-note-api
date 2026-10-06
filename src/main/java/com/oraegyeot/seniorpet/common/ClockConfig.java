package com.oraegyeot.seniorpet.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각의 출처. 기록 날짜·투약 시각·저장 시각은 모두 이 Clock 으로 구한다.
 * 테스트에서는 시각을 고정한 Clock(@Primary)으로 바꿔 새벽 4시 경계를 검증한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
