package com.oraegyeot.seniorpet.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매 분 0초(서울)에 발송 작업을 부른다. 로직은 ReminderDispatcher 에 있다. 스케줄러 스레드는 1개(순차 처리). */
@Component
@ConditionalOnProperty(name = "app.reminder.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderDispatcher dispatcher;

    public ReminderScheduler(ReminderDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void tick() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            log.error("투약 알림 발송 작업 실패(다음 분에 다시 시도)", e);
        }
    }
}
