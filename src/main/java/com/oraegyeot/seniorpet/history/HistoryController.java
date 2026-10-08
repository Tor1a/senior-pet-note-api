package com.oraegyeot.seniorpet.history;

import com.oraegyeot.seniorpet.history.HistoryDtos.HistoryResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 지난 기록 조회 API (docs/api-history.md). from/to 는 문자열로 받아 서비스에서 검증한다. */
@RestController
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/pets/{petId}/daily-logs")
    public HistoryResponse history(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to) {
        return historyService.history(userId, petId, from, to);
    }
}
