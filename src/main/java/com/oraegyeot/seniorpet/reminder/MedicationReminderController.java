package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.reminder.MedicationReminderDtos.ReminderRequest;
import com.oraegyeot.seniorpet.reminder.MedicationReminderDtos.ReminderResponse;
import com.oraegyeot.seniorpet.security.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 투약 알림 설정 API (docs/api-reminders.md 2절). */
@RestController
public class MedicationReminderController {

    private final MedicationReminderService reminderService;

    public MedicationReminderController(MedicationReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping("/api/medications/{id}/reminder")
    public ReminderResponse get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return ReminderResponse.from(reminderService.get(userId, id));
    }

    @PutMapping("/api/medications/{id}/reminder")
    public ReminderResponse put(@CurrentUserId UUID userId, @PathVariable UUID id,
                                @Valid @RequestBody ReminderRequest req) {
        return ReminderResponse.from(reminderService.put(userId, id, req));
    }
}
