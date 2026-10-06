package com.oraegyeot.seniorpet.dailylog;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 일일 기록 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface DailyLogRepository extends OwnedRepository<DailyLog, UUID> {

    Optional<DailyLog> findByPetIdAndUserIdAndRecordDate(UUID petId, UUID userId, LocalDate recordDate);

    /** 제안값 계산용: from ~ to (양 끝 포함) */
    List<DailyLog> findAllByPetIdAndUserIdAndRecordDateBetween(UUID petId, UUID userId,
                                                               LocalDate from, LocalDate to);

    /** recordDate 이전 기록 중 체중이 있는 가장 최근 1건 */
    Optional<DailyLog> findFirstByPetIdAndUserIdAndRecordDateBeforeAndWeightKgIsNotNullOrderByRecordDateDesc(
            UUID petId, UUID userId, LocalDate recordDate);
}
