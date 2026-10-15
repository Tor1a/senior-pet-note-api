package com.oraegyeot.seniorpet.medication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 약 시각 변경 이력 1건 (medication_schedule_versions 테이블, V7).
 * validFrom~validTo(양 끝 포함, 기록 날짜)에 이 times 가 유효했다. validTo == null 이면 지금도 유효(약당 최대 1개).
 * 기록 날짜는 서비스가 RecordDateCalculator 로 계산해 넣는다.
 */
@Entity
@Table(name = "medication_schedule_versions")
public class MedicationScheduleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "medication_id", nullable = false, updatable = false)
    private UUID medicationId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "time[]")
    private LocalTime[] times;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MedicationScheduleVersion() {
        // JPA 용
    }

    public MedicationScheduleVersion(UUID userId, UUID medicationId, List<LocalTime> times, LocalDate validFrom,
                                     Instant now) {
        this.userId = userId;
        this.medicationId = medicationId;
        this.times = times.stream().sorted().toArray(LocalTime[]::new);
        this.validFrom = validFrom;
        this.createdAt = now;
    }

    public void replaceTimes(List<LocalTime> times) {
        this.times = times.stream().sorted().toArray(LocalTime[]::new);
    }

    public void closeAt(LocalDate validTo) {
        this.validTo = validTo;
    }

    public UUID getMedicationId() {
        return medicationId;
    }

    /** 오름차순 시각 목록 */
    public List<LocalTime> getTimes() {
        return Arrays.stream(times).sorted().toList();
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    /** date(기록 날짜)에 유효한 버전인가 */
    public boolean covers(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }
}
