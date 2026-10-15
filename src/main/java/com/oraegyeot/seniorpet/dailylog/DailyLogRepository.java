package com.oraegyeot.seniorpet.dailylog;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 일일 기록 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface DailyLogRepository extends OwnedRepository<DailyLog, UUID> {

    Optional<DailyLog> findByPetIdAndUserIdAndRecordDate(UUID petId, UUID userId, LocalDate recordDate);

    /** 제안값 계산용: from ~ to (양 끝 포함) */
    List<DailyLog> findAllByPetIdAndUserIdAndRecordDateBetween(UUID petId, UUID userId,
                                                               LocalDate from, LocalDate to);

    /** recordDate 이전 기록 중 체중이 있는 가장 최근 1건 */
    Optional<DailyLog> findFirstByPetIdAndUserIdAndRecordDateBeforeAndWeightKgIsNotNullOrderByRecordDateDesc(
            UUID petId, UUID userId, LocalDate recordDate);

    /** 해당 반려동물의 가장 이른 일일 기록 날짜(첫 기록일). 기록이 없으면 empty */
    @Query("select min(d.recordDate) from DailyLog d where d.petId = :petId and d.userId = :userId")
    Optional<LocalDate> findFirstRecordDate(@Param("petId") UUID petId, @Param("userId") UUID userId);

    /** 하루치 기록 삭제(멱등: 없으면 0행). 동시 삭제에도 예외가 나지 않도록 단일 DELETE 로 처리한다. */
    @Modifying
    @Query("delete from DailyLog d where d.petId = :petId and d.userId = :userId and d.recordDate = :recordDate")
    int deleteByPetIdAndUserIdAndRecordDate(@Param("petId") UUID petId, @Param("userId") UUID userId,
                                            @Param("recordDate") LocalDate recordDate);
}
