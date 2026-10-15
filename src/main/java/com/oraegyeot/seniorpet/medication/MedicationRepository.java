package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 투약 일정 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface MedicationRepository extends OwnedRepository<Medication, UUID> {

    /** 반려동물의 활성 약 목록(등록 순) */
    List<Medication> findAllByPetIdAndUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(UUID petId, UUID userId);

    /** 활성 약 1건. 비활성(삭제된) 약은 없는 것으로 본다. */
    Optional<Medication> findByIdAndUserIdAndActiveTrue(UUID id, UUID userId);

    /** 활성 약 1건을 행 잠금(for update)으로 읽는다. 같은 약의 동시 변경·삭제가 이력을 꼬이게 하지 않도록 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Medication m where m.id = :id and m.userId = :userId and m.active = true")
    Optional<Medication> findLockedByIdAndUserIdAndActiveTrue(@Param("id") UUID id, @Param("userId") UUID userId);
}
