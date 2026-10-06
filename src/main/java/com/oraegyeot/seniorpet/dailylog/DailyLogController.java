package com.oraegyeot.seniorpet.dailylog;

import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogRequest;
import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 일일 기록 저장 API (docs/api-today.md 5절). recordDate 는 문자열로 받아 서비스에서 검증한다. */
@RestController
public class DailyLogController {

    private final DailyLogService dailyLogService;

    public DailyLogController(DailyLogService dailyLogService) {
        this.dailyLogService = dailyLogService;
    }

    @PutMapping("/api/pets/{petId}/daily-logs/{recordDate}")
    public DailyLogResponse upsert(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                   @PathVariable String recordDate, @Valid @RequestBody DailyLogRequest req) {
        return DailyLogResponse.from(dailyLogService.upsert(userId, petId, recordDate, req));
    }
}
