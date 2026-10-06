package com.oraegyeot.seniorpet.pet;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** 반려동물 API 요청·응답. 요청에 userId 필드는 없다(로그인 사용자로 정해짐). */
public final class PetDtos {

    private PetDtos() {
    }

    /** POST /api/pets, PUT /api/pets/{id} 요청(본문 형식이 같다). 제약은 DB CHECK 와 같다. */
    public record CreatePetRequest(
            @NotBlank(message = "이름을 입력하세요.")
            @Size(max = 30, message = "이름은 30자 이하여야 합니다.")
            String name,
            @NotNull(message = "종을 선택하세요.")
            @Pattern(regexp = "dog|cat", message = "종은 dog 또는 cat 이어야 합니다.")
            String species,
            @Min(value = 1980, message = "출생 연도는 1980 이상이어야 합니다.")
            @Max(value = 2100, message = "출생 연도는 2100 이하여야 합니다.")
            Short birthYear,
            @Size(max = 200, message = "질환은 200자 이하여야 합니다.")
            String conditions) {
    }

    /** 반려동물 응답. photo_path(내부 경로)는 노출하지 않고 사진 유무만 알려 준다. */
    public record PetResponse(
            UUID id,
            String name,
            String species,
            Short birthYear,
            String conditions,
            boolean hasPhoto,
            Instant createdAt,
            Instant updatedAt) {

        public static PetResponse from(Pet p) {
            return new PetResponse(p.getId(), p.getName(), p.getSpecies(), p.getBirthYear(),
                    p.getConditions(), p.getPhotoPath() != null, p.getCreatedAt(), p.getUpdatedAt());
        }
    }
}
