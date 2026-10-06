package com.oraegyeot.seniorpet.pet;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.pet.PetDtos.CreatePetRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반려동물 서비스 — "소유자 검증 서비스 패턴"의 기준 예시.
 *
 * 1) 모든 public 메서드의 첫 파라미터는 로그인 사용자 id(userId)다.
 * 2) 단건 접근은 getOwned(userId, id) 로만 한다. 남의 것/없는 것 모두 404 (존재 여부 비노출).
 * 3) 생성 시 엔티티의 userId 는 요청 본문이 아니라 파라미터 userId 로 채운다.
 * 다른 리소스(medications, daily_logs 등)의 서비스도 이 구조를 그대로 따른다.
 */
@Service
public class PetService {

    private final PetRepository petRepository;

    public PetService(PetRepository petRepository) {
        this.petRepository = petRepository;
    }

    @Transactional(readOnly = true)
    public List<Pet> list(UUID userId) {
        return petRepository.findAllByUserId(userId);
    }

    /** 본인 반려동물 1건. 다른 서비스(투약, 일일 기록)가 pet_id 를 받을 때도 이 메서드로 먼저 검증한다. */
    @Transactional(readOnly = true)
    public Pet getOwned(UUID userId, UUID petId) {
        return petRepository.findByIdAndUserId(petId, userId)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional
    public Pet create(UUID userId, CreatePetRequest req) {
        // MVP: 사용자당 1마리 (DB 의 pets_one_per_user 인덱스와 같은 규칙)
        if (petRepository.existsByUserId(userId)) {
            throw petLimit();
        }
        Pet pet = new Pet(userId, req.name().trim(), req.species(), req.birthYear(), blankToNull(req.conditions()));
        try {
            return petRepository.saveAndFlush(pet);
        } catch (DataIntegrityViolationException e) {
            throw petLimit(); // 동시 요청으로 unique 인덱스에 걸린 경우
        }
    }

    @Transactional
    public Pet update(UUID userId, UUID petId, CreatePetRequest req) {
        Pet pet = getOwned(userId, petId);
        pet.update(req.name().trim(), req.species(), req.birthYear(), blankToNull(req.conditions()));
        return petRepository.saveAndFlush(pet);
    }

    /** 사진 경로 교체 결과. oldPath 는 호출한 쪽이 DB 반영 뒤 파일을 지우는 데 쓴다. */
    public record PhotoChange(Pet pet, String oldPath) {
    }

    /** 사진 경로를 newPath(null 이면 삭제)로 바꾸고 이전 경로를 돌려준다. */
    @Transactional
    public PhotoChange changePhotoPath(UUID userId, UUID petId, String newPath) {
        Pet pet = getOwned(userId, petId);
        String oldPath = pet.getPhotoPath();
        pet.changePhotoPath(newPath);
        return new PhotoChange(petRepository.saveAndFlush(pet), oldPath);
    }

    private static ApiException petLimit() {
        return new ApiException(HttpStatus.CONFLICT, "PET_LIMIT_REACHED",
                "현재는 반려동물을 1마리만 등록할 수 있습니다.");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
