package com.oraegyeot.seniorpet.medlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 투약 체크 1건 (med_logs 테이블). 회차 = recordDate + scheduledTime.
 * recordDate·takenAt 은 서버가 RecordDateCalculator 로 정한다(클라이언트 값 사용 안 함).
 * created_at 은 DB 기본값(now())에 맡긴다.
 */
@Entity
@Table(name = "med_logs")
public class MedLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "medication_id", nullable = false, updatable = false)
    private UUID medicationId;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(name = "scheduled_time", nullable = false, updatable = false)
    private LocalTime scheduledTime;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    protected MedLog() {
        // JPA 용
    }

    public MedLog(UUID userId, UUID medicationId, LocalDate recordDate, LocalTime scheduledTime, Instant takenAt) {
        this.userId = userId;
        this.medicationId = medicationId;
        this.recordDate = recordDate;
        this.scheduledTime = scheduledTime;
        this.takenAt = takenAt;
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

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public LocalTime getScheduledTime() {
        return scheduledTime;
    }

    public Instant getTakenAt() {
        return takenAt;
    }
}
