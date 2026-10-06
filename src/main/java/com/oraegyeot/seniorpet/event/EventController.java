package com.oraegyeot.seniorpet.event;

import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 지표 이벤트 API: POST /api/events {name, props?} → 202 (본문 없음). */
@RestController
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /** props 는 JSON 객체만 받는다(배열·문자열이면 400). */
    public record EventRequest(@NotBlank(message = "이벤트 이름을 입력하세요.") String name,
                               Map<String, Object> props) {
    }

    @PostMapping("/api/events")
    public ResponseEntity<Void> record(@CurrentUserId UUID userId, @Valid @RequestBody EventRequest req) {
        eventService.record(userId, req.name(), req.props());
        return ResponseEntity.accepted().build();
    }
}
