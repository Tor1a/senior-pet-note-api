package com.oraegyeot.seniorpet.visitquestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 보호자가 직접 입력한 진료 때 물어볼 것 (visit_questions 테이블).
 * userId·petId 는 생성자에서 한 번만 정해진다(updatable = false, setter 없음).
 * text 는 건강 관련 내용이 들어갈 수 있는 글이라 로그·toString 에 싣지 않는다.
 */
@Entity
@Table(name = "visit_questions")
public class VisitQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private UUID petId;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VisitQuestion() {
        // JPA 용
    }

    public VisitQuestion(UUID userId, UUID petId, String text, Instant now) {
        this.userId = userId;
        this.petId = petId;
        this.createdAt = now;
        replace(text, now);
    }

    /** 전체 교체(완료 상태는 건드리지 않는다) */
    public void replace(String text, Instant now) {
        this.text = text;
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

    public String getText() {
        return text;
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
