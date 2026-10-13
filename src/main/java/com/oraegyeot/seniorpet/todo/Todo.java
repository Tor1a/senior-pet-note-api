package com.oraegyeot.seniorpet.todo;

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
 * 보호자가 직접 입력한 할 일 (todos 테이블).
 * userId·petId 는 생성자에서 한 번만 정해진다(updatable = false, setter 없음).
 * dueDate 는 기록 날짜 체계(새벽 4시 규칙)의 날짜다.
 */
@Entity
@Table(name = "todos")
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private UUID petId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "due_time")
    private LocalTime dueTime;

    @Column(columnDefinition = "text")
    private String memo;

    @Column(name = "hospital_note", columnDefinition = "text")
    private String hospitalNote;

    @Column(nullable = false)
    private boolean remind;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Todo() {
        // JPA 용
    }

    public Todo(UUID userId, UUID petId, String title, LocalDate dueDate, LocalTime dueTime, String memo,
                String hospitalNote, boolean remind, Instant now) {
        this.userId = userId;
        this.petId = petId;
        this.createdAt = now;
        replace(title, dueDate, dueTime, memo, hospitalNote, remind, now);
    }

    /** 전체 교체(완료 상태는 건드리지 않는다) */
    public void replace(String title, LocalDate dueDate, LocalTime dueTime, String memo, String hospitalNote,
                        boolean remind, Instant now) {
        this.title = title;
        this.dueDate = dueDate;
        this.dueTime = dueTime;
        this.memo = memo;
        this.hospitalNote = hospitalNote;
        this.remind = remind;
        this.updatedAt = now;
    }

    /** 완료 체크. 이미 완료면 최초 completedAt 을 유지한다(멱등). */
    public void complete(Instant now) {
        if (completedAt == null) {
            completedAt = now;
            updatedAt = now;
        }
    }

    /** 완료 취소(멱등) */
    public void reopen(Instant now) {
        if (completedAt != null) {
            completedAt = null;
            updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPetId() {
        return petId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalTime getDueTime() {
        return dueTime;
    }

    public String getMemo() {
        return memo;
    }

    public String getHospitalNote() {
        return hospitalNote;
    }

    public boolean isRemind() {
        return remind;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
