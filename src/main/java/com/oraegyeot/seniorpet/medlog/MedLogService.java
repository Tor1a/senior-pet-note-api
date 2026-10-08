package com.oraegyeot.seniorpet.medlog;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.medication.Medication;
import com.oraegyeot.seniorpet.medication.MedicationService;
import com.oraegyeot.seniorpet.medlog.MedLogDtos.MedLogRequest;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 투약 체크·취소. 기록 날짜는 RecordDateCalculator 가 정한다(새벽 4시 규칙). */
@Service
public class MedLogService {

    private final MedLogRepository medLogRepository;
    private final MedicationService medicationService;
    private final RecordDateCalculator recordDates;

    public MedLogService(MedLogRepository medLogRepository, MedicationService medicationService,
                         RecordDateCalculator recordDates) {
        this.medLogRepository = medLogRepository;
        this.medicationService = medicationService;
        this.recordDates = recordDates;
    }

    @Transactional
    public MedLog check(UUID userId, MedLogRequest req) {
        // 부모(약) 소유부터 검증. 남의 약·없는 약·비활성 약 = 404
        Medication med = medicationService.getOwnedActive(userId, req.medicationId());
        LocalTime slot = TimeOfDay.parse(req.scheduledTime());
        if (!med.getTimes().contains(slot)) {
            throw ApiException.validation("약 일정에 없는 투약 시각입니다.");
        }
        Instant now = recordDates.now();
        LocalDate recordDate = recordDates.recordDateOf(now);
        if (medLogRepository.existsByUserIdAndMedicationIdAndRecordDateAndScheduledTime(
                userId, med.getId(), recordDate, slot)) {
            throw alreadyChecked();
        }
        try {
            return medLogRepository.saveAndFlush(new MedLog(userId, med.getId(), recordDate, slot, now));
        } catch (DataIntegrityViolationException e) {
            throw alreadyChecked(); // 동시에 두 번 눌러 unique 제약에 걸린 경우
        }
    }

    @Transactional
    public void uncheck(UUID userId, UUID medLogId) {
        MedLog log = medLogRepository.findByIdAndUserId(medLogId, userId).orElseThrow(ApiException::notFound);
        medLogRepository.delete(log);
    }

    private static ApiException alreadyChecked() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_CHECKED", "이미 체크한 투약입니다.");
    }
}
