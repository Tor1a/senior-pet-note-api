package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 투약 일정 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface MedicationRepository extends OwnedRepository<Medication, UUID> {

    /** 반려동물의 활성 약 목록(등록 순) */
    List<Medication> findAllByPetIdAndUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(UUID petId, UUID userId);

    /** 활성 약 1건. 비활성(삭제된) 약은 없는 것으로 본다. */
    Optional<Medication> findByIdAndUserIdAndActiveTrue(UUID id, UUID userId);
}
