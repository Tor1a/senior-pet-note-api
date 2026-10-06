package com.oraegyeot.seniorpet.today;

import com.oraegyeot.seniorpet.security.CurrentUserId;
import com.oraegyeot.seniorpet.today.TodayDtos.TodayResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** "오늘" 화면 조회 API (docs/api-today.md 3절). */
@RestController
public class TodayController {

    private final TodayService todayService;

    public TodayController(TodayService todayService) {
        this.todayService = todayService;
    }

    @GetMapping("/api/pets/{petId}/today")
    public TodayResponse today(@CurrentUserId UUID userId, @PathVariable UUID petId) {
        return todayService.today(userId, petId);
    }
}
