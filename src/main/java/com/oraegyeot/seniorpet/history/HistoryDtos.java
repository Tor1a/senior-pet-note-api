package com.oraegyeot.seniorpet.history;

import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 지난 기록 조회 응답 (docs/api-history.md). */
public final class HistoryDtos {

    private HistoryDtos() {
    }

    /** 투약 회차 집계: 현재 활성 약 기준 예정 회차 수와 체크 수 (퍼센트 없음) */
    public record MedicationCount(int scheduledCount, int takenCount) {
    }

    /** 하루. dailyLog 가 null 이면 그날 기록 없음 */
    public record HistoryDay(LocalDate recordDate, DailyLogResponse dailyLog, MedicationCount medication) {
    }

    /** GET /api/pets/{petId}/daily-logs */
    public record HistoryResponse(UUID petId, LocalDate from, LocalDate to, LocalDate recordDate,
                                  String medicationBasis, List<HistoryDay> days) {
    }
}
