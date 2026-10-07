package com.oraegyeot.seniorpet.reminder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 켜기. app.reminder.scheduler-enabled=false(테스트 프로필)면 켜지 않는다
 * → 테스트 중 실제 시각의 tick 이 MutableClock 과 섞이지 않는다. 테스트는 ReminderDispatcher.dispatchDue() 를 직접 부른다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.reminder.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSchedulingConfig {
}
