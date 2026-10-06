package com.oraegyeot.seniorpet.event;

import com.oraegyeot.seniorpet.common.OwnedRepository;

/** 이벤트 저장소(추가 전용으로 쓴다). */
public interface EventRepository extends OwnedRepository<Event, Long> {
}
