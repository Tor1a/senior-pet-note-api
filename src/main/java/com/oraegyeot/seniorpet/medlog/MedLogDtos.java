package com.oraegyeot.seniorpet.medlog;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 투약 체크 API 요청·응답 (docs/api-today.md 4절). 요청에 날짜 필드는 없다(서버가 정함). */
public final class MedLogDtos {

    private MedLogDtos() {
    }

    public record MedLogRequest(
            @NotNull(message = "약을 선택하세요.")
            UUID medicationId,
            @NotNull(message = "투약 시각을 입력하세요.")
            @Pattern(regexp = TimeOfDay.REGEX, message = "투약 시각은 HH:mm 형식이어야 합니다.")
            String scheduledTime) {
    }

    /** {id, medicationId, recordDate, scheduledTime, takenAt} */
    public record MedLogResponse(UUID id, UUID medicationId, LocalDate recordDate, String scheduledTime,
                                 Instant takenAt) {

        public static MedLogResponse from(MedLog l) {
            return new MedLogResponse(l.getId(), l.getMedicationId(), l.getRecordDate(),
                    TimeOfDay.format(l.getScheduledTime()), l.getTakenAt());
        }
    }
}
