package com.oraegyeot.seniorpet.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oraegyeot.seniorpet.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 지표 이벤트 기록 (docs/api-today.md 6절). */
@Service
public class EventService {

    /** MVP 허용 이벤트 이름. 그 외는 400 */
    public static final Set<String> ALLOWED_NAMES = Set.of("today_opened", "med_checked", "daily_log_saved");

    /** props JSON 최대 크기(바이트). DB 제약(pg_column_size <= 2000)보다 작게 잡는다. */
    private static final int MAX_PROPS_BYTES = 1000;

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventService(EventRepository eventRepository, ObjectMapper objectMapper, Clock clock) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID userId, String name, Map<String, Object> props) {
        if (name == null || !ALLOWED_NAMES.contains(name)) {
            throw ApiException.validation("허용되지 않은 이벤트 이름입니다.");
        }
        Map<String, Object> safeProps = props == null ? Map.of() : props;
        try {
            if (objectMapper.writeValueAsString(safeProps).getBytes(StandardCharsets.UTF_8).length > MAX_PROPS_BYTES) {
                throw ApiException.validation("이벤트 props 가 너무 큽니다.");
            }
        } catch (JsonProcessingException e) {
            throw ApiException.validation("이벤트 props 형식이 올바르지 않습니다.");
        }
        eventRepository.save(new Event(userId, name, safeProps, clock.instant()));
    }
}
