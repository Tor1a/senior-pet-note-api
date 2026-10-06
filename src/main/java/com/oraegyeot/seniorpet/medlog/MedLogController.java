package com.oraegyeot.seniorpet.medlog;

import com.oraegyeot.seniorpet.medlog.MedLogDtos.MedLogRequest;
import com.oraegyeot.seniorpet.medlog.MedLogDtos.MedLogResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 투약 체크 API (docs/api-today.md 4절). */
@RestController
public class MedLogController {

    private final MedLogService medLogService;

    public MedLogController(MedLogService medLogService) {
        this.medLogService = medLogService;
    }

    @PostMapping("/api/med-logs")
    @ResponseStatus(HttpStatus.CREATED)
    public MedLogResponse check(@CurrentUserId UUID userId, @Valid @RequestBody MedLogRequest req) {
        return MedLogResponse.from(medLogService.check(userId, req));
    }

    @DeleteMapping("/api/med-logs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uncheck(@CurrentUserId UUID userId, @PathVariable UUID id) {
        medLogService.uncheck(userId, id);
    }
}
