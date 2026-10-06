package com.oraegyeot.seniorpet.dailylog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 일일 기록 (daily_logs 테이블). 반려동물당 하루 1행, 저장은 upsert.
 * 모든 측정 항목은 null = "안 적음". 해석·판단 값은 저장하지 않는다.
 */
@Entity
@Table(name = "daily_logs")
public class DailyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "pet_id", nullable = false, updatable = false)
    private UUID petId;

    @Column(name = "record_date", nullable = false, updatable = false)
    private LocalDate recordDate;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "water_ml")
    private Integer waterMl;

    @Column(name = "water_level")
    private Short waterLevel;

    @Column(name = "food_level")
    private Short foodLevel;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] symptoms;

    @Column(name = "symptoms_none", nullable = false)
    private boolean symptomsNone;

    @Column(name = "symptom_other", columnDefinition = "text")
    private String symptomOther;

    @Column(columnDefinition = "text")
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyLog() {
        // JPA 용
    }

    public DailyLog(UUID userId, UUID petId, LocalDate recordDate, Instant now) {
        this.userId = userId;
        this.petId = petId;
        this.recordDate = recordDate;
        this.symptoms = new String[0];
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** PUT 의 전체 교체. 값 검증은 DailyLogService 에서 끝난 상태로 들어온다. */
    public void replace(Short foodLevel, Short waterLevel, Integer waterMl, BigDecimal weightKg,
                        List<String> symptoms, boolean symptomsNone, String symptomOther, String memo,
                        Instant now) {
        this.foodLevel = foodLevel;
        this.waterLevel = waterLevel;
        this.waterMl = waterMl;
        this.weightKg = weightKg;
        this.symptoms = symptoms.toArray(String[]::new);
        this.symptomsNone = symptomsNone;
        this.symptomOther = symptomOther;
        this.memo = memo;
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

    public LocalDate getRecordDate() {
        return recordDate;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public Integer getWaterMl() {
        return waterMl;
    }

    public Short getWaterLevel() {
        return waterLevel;
    }

    public Short getFoodLevel() {
        return foodLevel;
    }

    public List<String> getSymptoms() {
        return symptoms == null ? List.of() : List.of(symptoms);
    }

    public boolean isSymptomsNone() {
        return symptomsNone;
    }

    public String getSymptomOther() {
        return symptomOther;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
