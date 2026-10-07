package com.oraegyeot.seniorpet.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 약별 알림 규칙 (medication_reminders 테이블, medications 와 1:1).
 * 알림 시각은 따로 두지 않고 약의 투약 시각(medications.times)을 쓴다.
 * userId·medicationId 는 생성자에서 한 번만 정해진다(updatable = false, setter 없음).
 */
@Entity
@Table(name = "medication_reminders")
public class MedicationReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "medication_id", nullable = false, updatable = false)
    private UUID medicationId;

    @Column(nullable = false)
    private boolean enabled;

    /** daily | weekly | interval */
    @Column(name = "repeat_type", nullable = false, columnDefinition = "text")
    private String repeatType;

    /** ISO 요일(1=월 … 7=일), 오름차순. weekly 가 아니면 빈 배열 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "days_of_week", nullable = false, columnDefinition = "smallint[]")
    private Short[] daysOfWeek;

    @Column(name = "interval_days")
    private Short intervalDays;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MedicationReminder() {
        // JPA 용
    }

    public MedicationReminder(UUID userId, UUID medicationId, ReminderRule rule, Instant now) {
        this.userId = userId;
        this.medicationId = medicationId;
        this.createdAt = now;
        replace(rule, now);
    }

    /** PUT 의 전체 교체. 규칙 검증은 MedicationReminderService 에서 끝난 상태로 들어온다. */
    public void replace(ReminderRule rule, Instant now) {
        this.enabled = rule.enabled();
        this.repeatType = rule.repeat().code();
        this.daysOfWeek = rule.daysOfWeek().stream().sorted()
                .map(d -> (short) d.getValue()).toArray(Short[]::new);
        this.intervalDays = rule.intervalDays() == null ? null : rule.intervalDays().shortValue();
        this.startDate = rule.startDate();
        this.endDate = rule.endDate();
        this.updatedAt = now;
    }

    public ReminderRule toRule() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (daysOfWeek != null) {
            Arrays.stream(daysOfWeek).forEach(d -> days.add(DayOfWeek.of(d)));
        }
        return new ReminderRule(enabled, RepeatType.fromCode(repeatType), days,
                intervalDays == null ? null : intervalDays.intValue(), startDate, endDate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getMedicationId() {
        return medicationId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
