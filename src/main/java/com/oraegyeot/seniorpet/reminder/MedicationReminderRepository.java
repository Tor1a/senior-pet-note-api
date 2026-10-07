package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.common.OwnedRepository;
import java.util.Optional;
import java.util.UUID;

/** 알림 규칙 저장소. 모든 조회에 userId 가 들어간다(OwnedRepository 규칙). 발송 작업용 조회는 ReminderDispatchQueries. */
public interface MedicationReminderRepository extends OwnedRepository<MedicationReminder, UUID> {

    Optional<MedicationReminder> findByMedicationIdAndUserId(UUID medicationId, UUID userId);
}
