package com.oraegyeot.seniorpet.pet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 반려동물 프로필 (pets 테이블).
 * userId 는 생성자에서 한 번만 정해지고 바뀌지 않는다(updatable = false, setter 없음).
 */
@Entity
@Table(name = "pets")
public class Pet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /** dog | cat */
    @Column(nullable = false, columnDefinition = "text")
    private String species;

    @Column(name = "birth_year")
    private Short birthYear;

    /** 질환(자유 입력) */
    @Column(columnDefinition = "text")
    private String conditions;

    /** 사진 파일 경로(저장소 내부 경로). 사진 업로드 API 에서만 바꾼다. */
    @Column(name = "photo_path", columnDefinition = "text")
    private String photoPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Pet() {
        // JPA 용
    }

    public Pet(UUID userId, String name, String species, Short birthYear, String conditions) {
        this.userId = userId;
        this.name = name;
        this.species = species;
        this.birthYear = birthYear;
        this.conditions = conditions;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** PUT /api/pets/{id} — 사진과 소유자는 바꾸지 않는다. */
    public void update(String name, String species, Short birthYear, String conditions) {
        this.name = name;
        this.species = species;
        this.birthYear = birthYear;
        this.conditions = conditions;
    }

    /** 사진 경로 교체·삭제(null). PetService 의 사진 메서드에서만 호출한다. */
    void changePhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now(); // DB 트리거도 갱신하지만 응답에 바로 반영하려고 같이 둔다
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getSpecies() {
        return species;
    }

    public Short getBirthYear() {
        return birthYear;
    }

    public String getConditions() {
        return conditions;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
