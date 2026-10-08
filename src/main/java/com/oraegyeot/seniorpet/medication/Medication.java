package com.oraegyeot.seniorpet.medication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 투약 일정 (medications 테이블). times 는 보호자 현지 시각(app.zone, 기본 Asia/Seoul) 1~3개, 오름차순으로 저장한다. */
@Entity
@Table(name = "medications")
public class Medication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private UUID petId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "dose_text", columnDefinition = "text")
    private String doseText;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "time[]")
    private LocalTime[] times;

    /** false = 삭제(비활성화). 과거 투약 기록은 남긴다. */
    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Medication() {
        // JPA 용
    }

    public Medication(UUID userId, UUID petId, String name, String doseText, List<LocalTime> times, Instant now) {
        this.userId = userId;
        this.petId = petId;
        this.active = true;
        this.createdAt = now;
        update(name, doseText, times, now);
    }

    public void update(String name, String doseText, List<LocalTime> times, Instant now) {
        this.name = name;
        this.doseText = doseText;
        this.times = times.stream().sorted().toArray(LocalTime[]::new);
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
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

    public String getName() {
        return name;
    }

    public String getDoseText() {
        return doseText;
    }

    /** 오름차순 시각 목록 */
    public List<LocalTime> getTimes() {
        return Arrays.stream(times).sorted().toList();
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
