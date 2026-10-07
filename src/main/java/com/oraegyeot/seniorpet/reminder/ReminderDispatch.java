package com.oraegyeot.seniorpet.reminder;

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
 * 알림 발송 기록 (reminder_dispatches 테이블). 회차당 1행 — 유니크 키가 중복 발송 방지의 근거.
 * 이 매핑은 스키마 검증(ddl-auto: validate)과 문서화용이다.
 * 쓰기(선점 insert ... on conflict do nothing, 결과 반영)는 ReminderDispatchQueries 의 SQL 로만 한다.
 */
@Entity
@Table(name = "reminder_dispatches")
public class ReminderDispatch {

    /** 선점 후 결과 반영 전(발송 중 서버가 죽으면 이 상태로 남고 재발송하지 않음) */
    public static final String CLAIMED = "claimed";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    /** 이미 투약 체크한 회차라 보내지 않음 */
    public static final String SKIPPED_TAKEN = "skipped_taken";
    /** 등록된 기기 토큰이 없음 */
    public static final String NO_DEVICE = "no_device";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "medication_id", nullable = false, updatable = false)
    private UUID medicationId;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(name = "scheduled_time", nullable = false, updatable = false)
    private LocalTime scheduledTime;

    @Column(name = "fire_at", nullable = false, updatable = false)
    private Instant fireAt;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "success_count", nullable = false)
    private short successCount;

    @Column(name = "failure_count", nullable = false)
    private short failureCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReminderDispatch() {
        // JPA 용
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
