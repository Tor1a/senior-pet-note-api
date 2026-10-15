package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 약 시각 변경 이력 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface MedicationScheduleVersionRepository extends OwnedRepository<MedicationScheduleVersion, UUID> {

    /** 약의 열린(현재 유효한) 버전 */
    Optional<MedicationScheduleVersion> findByMedicationIdAndUserIdAndValidToIsNull(UUID medicationId, UUID userId);

    /** 반려동물의 약(삭제한 약 포함) 중 [from, to] 기록 날짜 범위와 겹치는 버전. 약은 user_id·pet_id 로 거른다. */
    @Query("""
            select v from MedicationScheduleVersion v
            where v.userId = :userId
              and v.medicationId in (select m.id from Medication m where m.petId = :petId and m.userId = :userId)
              and v.validFrom <= :to
              and (v.validTo is null or v.validTo >= :from)
            order by v.medicationId, v.validFrom
            """)
    List<MedicationScheduleVersion> findOverlappingByUserIdAndPetId(@Param("userId") UUID userId,
                                                                     @Param("petId") UUID petId,
                                                                     @Param("from") LocalDate from,
                                                                     @Param("to") LocalDate to);

    /** 테스트·검증용: 약의 모든 버전 */
    List<MedicationScheduleVersion> findAllByMedicationIdAndUserIdOrderByValidFromAsc(UUID medicationId, UUID userId);
}
