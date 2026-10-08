package com.oraegyeot.seniorpet.medlog;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 투약 체크 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). */
public interface MedLogRepository extends OwnedRepository<MedLog, UUID> {

    /** 특정 기록 날짜의 체크 목록("오늘" 화면용) */
    List<MedLog> findAllByUserIdAndRecordDateAndMedicationIdIn(UUID userId, LocalDate recordDate,
                                                               Collection<UUID> medicationIds);

    /** 기간(양 끝 포함) 체크 목록("지난 기록" 화면용) */
    List<MedLog> findAllByUserIdAndRecordDateBetweenAndMedicationIdIn(UUID userId, LocalDate from, LocalDate to,
                                                                      Collection<UUID> medicationIds);

    boolean existsByUserIdAndMedicationIdAndRecordDateAndScheduledTime(UUID userId, UUID medicationId,
                                                                       LocalDate recordDate, LocalTime scheduledTime);
}
