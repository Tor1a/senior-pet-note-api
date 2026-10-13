package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** app.todo.*-time 이 04:00(기록 날짜 경계) 미만이면 서버 시작이 실패한다. */
class TodoConfigTest {

    @Configuration
    @EnableConfigurationProperties(TodoProperties.class)
    static class Cfg {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Cfg.class);

    @Test
    void 기본값은_20시와_9시() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            TodoProperties p = ctx.getBean(TodoProperties.class);
            assertThat(p.dayBeforeTime()).isEqualTo(LocalTime.of(20, 0));
            assertThat(p.dueDayTime()).isEqualTo(LocalTime.of(9, 0));
        });
    }

    @Test
    void yml_문자열_HH_mm_이_바인딩된다() {
        runner.withPropertyValues("app.todo.day-before-time=18:30", "app.todo.due-day-time=04:00").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            TodoProperties p = ctx.getBean(TodoProperties.class);
            assertThat(p.dayBeforeTime()).isEqualTo(LocalTime.of(18, 30));
            assertThat(p.dueDayTime()).isEqualTo(LocalTime.of(4, 0)); // 경계 04:00 은 허용
        });
    }

    @Test
    void 하루_전_시각이_04시_미만이면_기동_실패() {
        runner.withPropertyValues("app.todo.day-before-time=03:59").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("app.todo.day-before-time");
        });
    }

    @Test
    void 당일_시각이_04시_미만이면_기동_실패() {
        runner.withPropertyValues("app.todo.due-day-time=00:30").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("app.todo.due-day-time");
        });
    }

    @Test
    void 생성자도_같은_검사() {
        assertThatThrownBy(() -> new TodoProperties(LocalTime.of(3, 59), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TodoProperties(null, LocalTime.MIDNIGHT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TodoProperties(null, null).dayBeforeTime()).isEqualTo(LocalTime.of(20, 0));
    }
}
