package com.oraegyeot.seniorpet.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 발송기 선택(FCM_ENABLED) 테스트. Spring 컨텍스트만 띄우고 DB·네트워크는 쓰지 않는다. */
class PushSenderConfigTest {

    @Configuration
    @EnableConfigurationProperties(FcmProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PushConfig.class, PropsConfig.class)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void FCM_설정이_없으면_로그_발송기() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(PushSender.class);
            assertThat(ctx.getBean(PushSender.class)).isInstanceOf(LoggingPushSender.class);
        });
        runner.withPropertyValues("app.push.fcm.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(PushSender.class)).isInstanceOf(LoggingPushSender.class));
    }

    @Test
    void FCM_켜고_자격증명이_없으면_시작_실패() {
        runner.withPropertyValues("app.push.fcm.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("FCM_CREDENTIALS_BASE64");
        });
    }

    @Test
    void FCM_켜고_자격증명이_깨졌으면_시작_실패() {
        runner.withPropertyValues("app.push.fcm.enabled=true", "app.push.fcm.credentials-base64=%%%broken%%%")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("app.push.fcm.enabled=true", "app.push.fcm.credentials-base64=aGVsbG8=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void FCM_켜고_형식이_맞는_키면_FCM_발송기() {
        runner.withPropertyValues("app.push.fcm.enabled=true",
                        "app.push.fcm.credentials-base64=" + FakeServiceAccount.base64())
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PushSender.class);
                    assertThat(ctx.getBean(PushSender.class)).isInstanceOf(FcmPushSender.class);
                    // 비밀값이 설정 객체의 문자열에 드러나지 않는다
                    assertThat(ctx.getBean(FcmProperties.class).toString()).doesNotContain("PRIVATE").contains("(설정됨)");
                });
    }

    @Test
    void 로그_발송기는_모두_성공으로_돌려주고_토큰을_가린다() {
        var results = new LoggingPushSender().send(List.of("abcdefghijklmnop", "short"),
                new PushMessage("제목", "본문", Map.of(), "k"));
        assertThat(results).extracting(PushResult::status).containsOnly(PushResult.Status.SUCCESS);
        assertThat(LoggingPushSender.mask("abcdefghijklmnop")).isEqualTo("abcdefgh…");
        assertThat(LoggingPushSender.mask("short")).isEqualTo("****");
    }
}
