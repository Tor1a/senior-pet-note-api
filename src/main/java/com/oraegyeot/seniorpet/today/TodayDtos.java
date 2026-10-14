package com.oraegyeot.seniorpet.today;

import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** GET /api/pets/{petId}/today 응답 (docs/api-today.md 3절). */
public final class TodayDtos {

    private TodayDtos() {
    }

    public record TodayResponse(
            LocalDate recordDate,
            String cutoffNotice,
            List<Dose> doses,
            DailyLogResponse dailyLog,
            SuggestionsResponse suggestions,
            LastWeight lastWeight,
            Companion companion) {
    }

    /** 함께한 날·기록 시작 경과일 (docs/api-companion.md). first_time 이 아니면 응답의 companion 은 null. */
    public record Companion(Integer togetherDays, int recordingDays) {
    }

    /** 약 1회차. 체크 전이면 taken=false, medLogId/takenAt=null */
    public record Dose(UUID medicationId, String name, String doseText, String scheduledTime,
                       boolean taken, UUID medLogId, Instant takenAt) {
    }

    public record SuggestionsResponse(Integer foodLevel, Integer waterLevel, Integer waterMl, Double weightKg) {
    }

    public record LastWeight(Double weightKg, LocalDate recordDate) {
    }
}
