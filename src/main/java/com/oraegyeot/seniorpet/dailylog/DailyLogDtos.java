package com.oraegyeot.seniorpet.dailylog;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 일일 기록 API 요청·응답 (docs/api-today.md 5절). 검증은 DB CHECK 제약과 같다. */
public final class DailyLogDtos {

    private DailyLogDtos() {
    }

    /** 허용 증상 코드 */
    public static final String SYMPTOM_REGEX = "vomit|diarrhea|cough|lethargy|seizure|other";

    /** PUT /api/pets/{petId}/daily-logs/{recordDate} 요청 */
    public record DailyLogRequest(
            @Min(value = 1, message = "식사는 1~3 이어야 합니다.")
            @Max(value = 3, message = "식사는 1~3 이어야 합니다.")
            Integer foodLevel,
            @Min(value = 1, message = "음수는 1~3 이어야 합니다.")
            @Max(value = 3, message = "음수는 1~3 이어야 합니다.")
            Integer waterLevel,
            @Min(value = 0, message = "음수량은 0~20000ml 여야 합니다.")
            @Max(value = 20000, message = "음수량은 0~20000ml 여야 합니다.")
            Integer waterMl,
            @DecimalMin(value = "0", inclusive = false, message = "체중은 0kg 초과여야 합니다.")
            @DecimalMax(value = "200", inclusive = false, message = "체중은 200kg 미만이어야 합니다.")
            BigDecimal weightKg,
            @Size(max = 6, message = "증상이 너무 많습니다.")
            List<@NotNull(message = "증상 값이 비어 있습니다.")
                 @Pattern(regexp = SYMPTOM_REGEX, message = "알 수 없는 증상입니다.") String> symptoms,
            Boolean symptomsNone,
            @Size(max = 30, message = "기타 증상은 30자 이하여야 합니다.")
            String symptomOther,
            @Size(max = 200, message = "메모는 200자 이하여야 합니다.")
            String memo) {
    }

    /** DailyLog 응답 */
    public record DailyLogResponse(
            UUID id,
            UUID petId,
            LocalDate recordDate,
            Integer foodLevel,
            Integer waterLevel,
            Integer waterMl,
            Double weightKg,
            List<String> symptoms,
            boolean symptomsNone,
            String symptomOther,
            String memo,
            Instant updatedAt) {

        public static DailyLogResponse from(DailyLog d) {
            return new DailyLogResponse(d.getId(), d.getPetId(), d.getRecordDate(),
                    toInt(d.getFoodLevel()), toInt(d.getWaterLevel()), d.getWaterMl(),
                    d.getWeightKg() == null ? null : d.getWeightKg().doubleValue(),
                    d.getSymptoms(), d.isSymptomsNone(), d.getSymptomOther(), d.getMemo(), d.getUpdatedAt());
        }

        private static Integer toInt(Short s) {
            return s == null ? null : s.intValue();
        }
    }
}
