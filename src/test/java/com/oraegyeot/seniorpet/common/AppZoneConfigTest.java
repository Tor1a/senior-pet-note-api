package com.oraegyeot.seniorpet.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** app.zone 설정 검증. Spring 컨텍스트만 띄우고 DB 는 쓰지 않는다. */
class AppZoneConfigTest {

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 설정이_없으면_기본값_Asia_Seoul() {
        runner.run(ctx -> assertThat(ctx.getBean(AppProperties.class).zoneId().getId()).isEqualTo("Asia/Seoul"));
        runner.withPropertyValues("app.zone=").run(ctx ->
                assertThat(ctx.getBean(AppProperties.class).zoneId().getId()).isEqualTo("Asia/Seoul"));
    }

    @Test
    void 유효한_시간대는_그대로_쓴다() {
        for (String zone : new String[] {"UTC", "Asia/Seoul", "America/New_York"}) {
            runner.withPropertyValues("app.zone=" + zone).run(ctx ->
                    assertThat(ctx.getBean(AppProperties.class).zoneId().getId()).isEqualTo(zone));
        }
    }

    @Test
    void 잘못된_시간대면_명확한_메시지로_시작_실패() {
        for (String zone : new String[] {"Mars/Base", "KST9", "서울"}) {
            runner.withPropertyValues("app.zone=" + zone).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("app.zone(APP_ZONE)");
            });
        }
    }
}
