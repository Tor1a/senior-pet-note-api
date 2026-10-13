package com.oraegyeot.seniorpet.pet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
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

    /** first_time | senior | null(선택 안 함). 서비스는 이 값으로 판단하지 않고 화면 노출만 정한다. */
    @Column(name = "profile_type", columnDefinition = "text")
    private String profileType;

    /** 정확한 생일(달력 날짜). 있으면 연도가 birthYear 와 같다. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** 입양일(달력 날짜) */
    @Column(name = "adopted_on")
    private LocalDate adoptedOn;

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

    public Pet(UUID userId, String name, String species, Short birthYear, String conditions,
               String profileType, LocalDate birthDate, LocalDate adoptedOn) {
        this.userId = userId;
        this.name = name;
        this.species = species;
        this.birthYear = birthYear;
        this.conditions = conditions;
        this.profileType = profileType;
        this.birthDate = birthDate;
        this.adoptedOn = adoptedOn;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** PUT /api/pets/{id} — 사진과 소유자는 바꾸지 않는다. */
    public void update(String name, String species, Short birthYear, String conditions,
                       String profileType, LocalDate birthDate, LocalDate adoptedOn) {
        this.name = name;
        this.species = species;
        this.birthYear = birthYear;
        this.conditions = conditions;
        this.profileType = profileType;
        this.birthDate = birthDate;
        this.adoptedOn = adoptedOn;
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

    public String getProfileType() {
        return profileType;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public LocalDate getAdoptedOn() {
        return adoptedOn;
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
