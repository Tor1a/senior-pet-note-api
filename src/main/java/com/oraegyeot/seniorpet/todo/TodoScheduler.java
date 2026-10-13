package com.oraegyeot.seniorpet.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매 분 0초에 할 일 알림 발송 작업을 부른다. 로직은 TodoDispatcher 에 있다.
 * 켜짐 조건은 투약 알림과 같은 app.reminder.scheduler-enabled(테스트 프로필은 꺼짐). 스케줄러 스레드는 1개라 투약 발송과 순차 실행된다.
 */
@Component
@ConditionalOnProperty(name = "app.reminder.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class TodoScheduler {

    private static final Logger log = LoggerFactory.getLogger(TodoScheduler.class);

    private final TodoDispatcher dispatcher;

    public TodoScheduler(TodoDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException e) {
            log.error("할 일 알림 발송 작업 실패(다음 분에 다시 시도)", e);
        }
    }
}
