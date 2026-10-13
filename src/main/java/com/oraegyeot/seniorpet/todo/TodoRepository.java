package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 할 일 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). 발송 작업용 조회는 TodoDispatchQueries. */
public interface TodoRepository extends OwnedRepository<Todo, UUID> {

    /** 미완료 목록: 날짜 → 시각(없으면 뒤) → 등록순 */
    @Query("""
            select t from Todo t
             where t.userId = :userId and t.petId = :petId and t.completedAt is null
             order by t.dueDate asc, case when t.dueTime is null then 1 else 0 end asc, t.dueTime asc, t.createdAt asc
            """)
    List<Todo> findOpenByUserId(@Param("userId") UUID userId, @Param("petId") UUID petId);

    /** 완료 목록: 최근 완료 순, 최대 100개 */
    List<Todo> findTop100ByUserIdAndPetIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(UUID userId, UUID petId);

    long countByUserId(UUID userId);
}
