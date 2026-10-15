package com.oraegyeot.seniorpet.visitquestion;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.List;
import java.util.UUID;

/** 진료 질문 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). 전 사용자 대상 쿼리는 없다. */
public interface VisitQuestionRepository extends OwnedRepository<VisitQuestion, UUID> {

    /** 미완료 목록: 등록 순(오래된 것 위) */
    List<VisitQuestion> findByUserIdAndPetIdAndCompletedAtIsNullOrderByCreatedAtAscIdAsc(UUID userId, UUID petId);

    /** 완료 목록: 최근 완료 순 */
    List<VisitQuestion> findByUserIdAndPetIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdAsc(UUID userId, UUID petId);

    long countByUserId(UUID userId);
}
