package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 투약 일정 API 요청·응답 (docs/api-today.md 2절). */
public final class MedicationDtos {

    private MedicationDtos() {
    }

    /** POST /api/pets/{petId}/medications, PUT /api/medications/{id} 요청 */
    public record MedicationRequest(
            @NotBlank(message = "약 이름을 입력하세요.")
            @Size(max = 50, message = "약 이름은 50자 이하여야 합니다.")
            String name,
            @Size(max = 50, message = "용량은 50자 이하여야 합니다.")
            String doseText,
            @NotNull(message = "투약 시각을 입력하세요.")
            @Size(min = 1, max = 3, message = "투약 시각은 1~3개여야 합니다.")
            List<@NotNull(message = "투약 시각이 비어 있습니다.")
                 @Pattern(regexp = TimeOfDay.REGEX, message = "투약 시각은 HH:mm 형식이어야 합니다.") String> times) {
    }

    /** Medication = {id, petId, name, doseText, times:["08:00"], active} */
    public record MedicationResponse(UUID id, UUID petId, String name, String doseText, List<String> times,
                                     boolean active) {

        public static MedicationResponse from(Medication m) {
            return new MedicationResponse(m.getId(), m.getPetId(), m.getName(), m.getDoseText(),
                    m.getTimes().stream().map(TimeOfDay::format).toList(), m.isActive());
        }
    }
}
