package com.oraegyeot.seniorpet.todo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 할 일 알림 발송 기록 (todo_dispatches 테이블). (할 일, 종류, 예정 날짜)당 1행 — 유니크 키가 중복 발송 방지의 근거.
 * 이 매핑은 스키마 검증(ddl-auto: validate)과 문서화용이다.
 * 쓰기(선점 insert ... on conflict do nothing, 결과 반영)는 TodoDispatchQueries 의 SQL 로만 한다.
 */
@Entity
@Table(name = "todo_dispatches")
public class TodoDispatch {

    /** 예정 날짜 하루 전 알림 */
    public static final String DAY_BEFORE = "day_before";
    /** 예정 날짜 당일 알림 */
    public static final String DUE_DAY = "due_day";

    /** 선점 후 결과 반영 전(발송 중 서버가 죽으면 이 상태로 남고 재발송하지 않음) */
    public static final String CLAIMED = "claimed";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    /** 선점 뒤 완료·삭제·알림 끔·예정 날짜 변경으로 보낼 상태가 아니게 돼 보내지 않음 */
    public static final String SKIPPED_DONE = "skipped_done";
    /** 등록된 기기 토큰이 없음 */
    public static final String NO_DEVICE = "no_device";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "todo_id", nullable = false, updatable = false)
    private UUID todoId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String kind;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

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

    protected TodoDispatch() {
        // JPA 용
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
