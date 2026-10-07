package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 투약 알림 API 요청·응답 (docs/api-reminders.md 2절). */
public final class MedicationReminderDtos {

    private MedicationReminderDtos() {
    }

    /** 요일 코드. 인덱스 0 = 월요일(ISO 1) */
    static final List<String> DAY_CODES = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    public static final String DAY_REGEX = "mon|tue|wed|thu|fri|sat|sun";

    static DayOfWeek parseDay(String code) {
        return DayOfWeek.of(DAY_CODES.indexOf(code) + 1);
    }

    static String dayCode(DayOfWeek day) {
        return DAY_CODES.get(day.getValue() - 1);
    }

    /** PUT /api/medications/{id}/reminder 요청(전체 교체). userId 필드는 두지 않는다. */
    public record ReminderRequest(
            @NotNull(message = "알림 사용 여부(enabled)를 입력하세요.")
            Boolean enabled,
            @NotNull(message = "반복 방식을 입력하세요.")
            @Pattern(regexp = RepeatType.REGEX, message = "반복 방식은 daily, weekly, interval 중 하나여야 합니다.")
            String repeat,
            @Size(max = 7, message = "요일은 7개 이하여야 합니다.")
            List<@NotNull(message = "요일 값이 비어 있습니다.")
                 @Pattern(regexp = DAY_REGEX, message = "요일은 mon~sun 중 하나여야 합니다.") String> daysOfWeek,
            @Min(value = 2, message = "간격은 2~30일이어야 합니다.")
            @Max(value = 30, message = "간격은 2~30일이어야 합니다.")
            Integer intervalDays,
            LocalDate startDate,
            LocalDate endDate) {
    }

    /** Reminder = {medicationId, enabled, repeat, daysOfWeek, intervalDays, startDate, endDate, times, nextFireAt, updatedAt} */
    public record ReminderResponse(UUID medicationId, boolean enabled, String repeat, List<String> daysOfWeek,
                                   Integer intervalDays, LocalDate startDate, LocalDate endDate, List<String> times,
                                   Instant nextFireAt, Instant updatedAt) {

        public static ReminderResponse from(MedicationReminderService.ReminderView v) {
            ReminderRule r = v.rule();
            return new ReminderResponse(v.medication().getId(), r.enabled(), r.repeat().code(),
                    r.daysOfWeek().stream().sorted().map(MedicationReminderDtos::dayCode).toList(),
                    r.intervalDays(), r.startDate(), r.endDate(),
                    v.medication().getTimes().stream().map(TimeOfDay::format).toList(),
                    v.nextFireAt(), v.updatedAt());
        }
    }
}
