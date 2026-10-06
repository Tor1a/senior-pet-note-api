package com.oraegyeot.seniorpet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트용 시계. set() 으로 시각을 고정하고, reset() 하면 실제 시각으로 돌아간다.
 * ApiTestSupport 가 @Primary Clock 으로 등록하고 테스트마다 reset 한다.
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public Instant instant() {
        Instant f = fixed;
        return f != null ? f : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
