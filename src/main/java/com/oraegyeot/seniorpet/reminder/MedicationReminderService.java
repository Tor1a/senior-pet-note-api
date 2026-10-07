package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.medication.Medication;
import com.oraegyeot.seniorpet.medication.MedicationService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.reminder.MedicationReminderDtos.ReminderRequest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 투약 알림 규칙 조회·설정(upsert). 소유자 검증은 약 서비스의 getOwnedActive 로 먼저 한다(남의 약·비활성 약 404).
 * 반복 규칙 판정·다음 발송 시각 계산은 ReminderRule, 날짜·시각 계산은 RecordDateCalculator 가 한다.
 */
@Service
public class MedicationReminderService {

    private final MedicationReminderRepository reminderRepository;
    private final MedicationService medicationService;
    private final RecordDateCalculator recordDates;
    private final TransactionTemplate tx;

    public MedicationReminderService(MedicationReminderRepository reminderRepository,
                                     MedicationService medicationService, RecordDateCalculator recordDates,
                                     PlatformTransactionManager txManager) {
        this.reminderRepository = reminderRepository;
        this.medicationService = medicationService;
        this.recordDates = recordDates;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 응답 재료. updatedAt == null 이면 아직 설정하지 않은 약(기본값). */
    public record ReminderView(Medication medication, ReminderRule rule, Instant nextFireAt, Instant updatedAt) {
    }

    /** 설정 전이면 기본값(꺼짐, daily, 시작일 = 현재 기록 날짜)을 돌려준다. */
    public ReminderView get(UUID userId, UUID medicationId) {
        Medication med = medicationService.getOwnedActive(userId, medicationId);
        return reminderRepository.findByMedicationIdAndUserId(med.getId(), userId)
                .map(r -> view(med, r))
                .orElseGet(() -> new ReminderView(med, new ReminderRule(false, RepeatType.DAILY,
                        EnumSet.noneOf(DayOfWeek.class), null, recordDates.currentRecordDate(), null), null, null));
    }

    /** 전체 교체(upsert). 약 1개당 규칙 1개. */
    public ReminderView put(UUID userId, UUID medicationId, ReminderRequest req) {
        Medication med = medicationService.getOwnedActive(userId, medicationId); // 부모 소유부터 검증
        ReminderRule rule = validate(req);
        MedicationReminder saved;
        try {
            saved = tx.execute(s -> save(userId, med.getId(), rule));
        } catch (DataIntegrityViolationException e) {
            // 같은 약의 첫 설정이 동시에 들어와 unique 제약에 걸린 경우: 생긴 행을 갱신한다
            saved = tx.execute(s -> save(userId, med.getId(), rule));
        }
        return view(med, saved);
    }

    private MedicationReminder save(UUID userId, UUID medicationId, ReminderRule rule) {
        Instant now = recordDates.now();
        MedicationReminder r = reminderRepository.findByMedicationIdAndUserId(medicationId, userId)
                .orElse(null);
        if (r == null) {
            r = new MedicationReminder(userId, medicationId, rule, now);
        } else {
            r.replace(rule, now);
        }
        return reminderRepository.saveAndFlush(r);
    }

    private ReminderView view(Medication med, MedicationReminder r) {
        ReminderRule rule = r.toRule();
        return new ReminderView(med, rule, rule.nextFireAt(recordDates.now(), med.getTimes()), r.getUpdatedAt());
    }

    /** DTO 어노테이션으로 못 거르는 규칙: repeat 별 필드 조합, 요일 중복, 종료일 ≥ 시작일 */
    private ReminderRule validate(ReminderRequest req) {
        RepeatType repeat = RepeatType.fromCode(req.repeat());
        List<String> dayCodes = req.daysOfWeek() == null ? List.of() : req.daysOfWeek();
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (repeat == RepeatType.WEEKLY) {
            if (dayCodes.isEmpty()) {
                throw ApiException.validation("요일을 1개 이상 선택하세요.");
            }
            if (new HashSet<>(dayCodes).size() != dayCodes.size()) {
                throw ApiException.validation("요일이 중복되었습니다.");
            }
            dayCodes.forEach(c -> days.add(MedicationReminderDtos.parseDay(c)));
        } else if (!dayCodes.isEmpty()) {
            throw ApiException.validation("요일(daysOfWeek)은 반복 방식이 weekly 일 때만 보낼 수 있습니다.");
        }
        if (repeat == RepeatType.INTERVAL && req.intervalDays() == null) {
            throw ApiException.validation("간격(intervalDays)을 입력하세요.");
        }
        if (repeat != RepeatType.INTERVAL && req.intervalDays() != null) {
            throw ApiException.validation("간격(intervalDays)은 반복 방식이 interval 일 때만 보낼 수 있습니다.");
        }
        LocalDate start = req.startDate() != null ? req.startDate() : recordDates.currentRecordDate();
        if (req.endDate() != null && req.endDate().isBefore(start)) {
            throw ApiException.validation("종료일은 시작일보다 빠를 수 없습니다.");
        }
        return new ReminderRule(req.enabled(), repeat, days, req.intervalDays(), start, req.endDate());
    }
}
