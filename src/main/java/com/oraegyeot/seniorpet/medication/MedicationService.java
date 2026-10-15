package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.medication.MedicationDtos.MedicationRequest;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 투약 일정 서비스. PetService 와 같은 소유자 검증 패턴(첫 파라미터 userId, getOwned → 404)을 따른다. */
@Service
public class MedicationService {

    private final MedicationRepository medicationRepository;
    private final PetService petService;
    private final MedicationScheduleVersionRepository versionRepository;
    private final RecordDateCalculator recordDates;
    private final Clock clock;

    public MedicationService(MedicationRepository medicationRepository, PetService petService,
                             MedicationScheduleVersionRepository versionRepository,
                             RecordDateCalculator recordDates, Clock clock) {
        this.medicationRepository = medicationRepository;
        this.petService = petService;
        this.versionRepository = versionRepository;
        this.recordDates = recordDates;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Medication> listActive(UUID userId, UUID petId) {
        petService.getOwned(userId, petId);
        return medicationRepository.findAllByPetIdAndUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(petId, userId);
    }

    /** 본인의 활성 약 1건. 남의 것·없는 것·비활성 모두 404. */
    @Transactional(readOnly = true)
    public Medication getOwnedActive(UUID userId, UUID medicationId) {
        return medicationRepository.findByIdAndUserIdAndActiveTrue(medicationId, userId)
                .orElseThrow(ApiException::notFound);
    }

    /** 약 등록 + 일정 이력 첫 버전(오늘 기록 날짜부터). 같은 트랜잭션. */
    @Transactional
    public Medication create(UUID userId, UUID petId, MedicationRequest req) {
        petService.getOwned(userId, petId); // 부모 소유부터 검증
        Instant now = clock.instant();
        Medication m = new Medication(userId, petId, req.name().trim(), blankToNull(req.doseText()),
                parseTimes(req.times()), now);
        medicationRepository.saveAndFlush(m);
        versionRepository.saveAndFlush(new MedicationScheduleVersion(userId, m.getId(), m.getTimes(),
                recordDates.recordDateOf(now), now));
        return m;
    }

    /**
     * 수정. 시각 집합이 바뀐 경우에만 일정 이력을 갱신한다(이름·용량만 바꾸면 이력 불변).
     * 변경한 기록 날짜 D 부터 새 시각이 적용된다. 같은 날 여러 번 바꾸면 그날 버전을 제자리 갱신해 0일짜리 버전을 만들지 않는다.
     */
    @Transactional
    public Medication update(UUID userId, UUID medicationId, MedicationRequest req) {
        Medication m = getOwnedActiveForUpdate(userId, medicationId);
        List<LocalTime> before = m.getTimes();
        Instant now = clock.instant();
        m.update(req.name().trim(), blankToNull(req.doseText()), parseTimes(req.times()), now);
        medicationRepository.saveAndFlush(m);
        if (!before.equals(m.getTimes())) {
            changeSchedule(userId, m, recordDates.recordDateOf(now), now);
        }
        return m;
    }

    /** 삭제 = 비활성화. 과거 med_logs 와 일정 이력은 그대로 남아 삭제 전 날짜 집계에 쓰인다. */
    @Transactional
    public void deactivate(UUID userId, UUID medicationId) {
        Medication m = getOwnedActiveForUpdate(userId, medicationId);
        Instant now = clock.instant();
        m.deactivate(now);
        medicationRepository.saveAndFlush(m);
        LocalDate today = recordDates.recordDateOf(now);
        versionRepository.findByMedicationIdAndUserIdAndValidToIsNull(medicationId, userId).ifPresent(open -> {
            if (!open.getValidFrom().isBefore(today)) {
                versionRepository.delete(open); // 오늘 만든 약을 오늘 지움 = 과거에 예정 없음
            } else {
                open.closeAt(today.minusDays(1));
                versionRepository.saveAndFlush(open);
            }
        });
    }

    /** 변경 직렬화를 위해 약 행을 잠그고 읽는다. 남의 것·없는 것·비활성 모두 404. */
    private Medication getOwnedActiveForUpdate(UUID userId, UUID medicationId) {
        return medicationRepository.findLockedByIdAndUserIdAndActiveTrue(medicationId, userId)
                .orElseThrow(ApiException::notFound);
    }

    private void changeSchedule(UUID userId, Medication m, LocalDate today, Instant now) {
        MedicationScheduleVersion open = versionRepository
                .findByMedicationIdAndUserIdAndValidToIsNull(m.getId(), userId).orElse(null);
        if (open == null) {
            versionRepository.saveAndFlush(new MedicationScheduleVersion(userId, m.getId(), m.getTimes(), today, now));
        } else if (!open.getValidFrom().isBefore(today)) {
            open.replaceTimes(m.getTimes()); // 오늘(또는 시계가 되감긴 경우) 시작한 버전은 제자리 갱신
            versionRepository.saveAndFlush(open);
        } else {
            open.closeAt(today.minusDays(1));
            versionRepository.saveAndFlush(open); // 열린 버전 unique 인덱스 때문에 닫는 쪽을 먼저 반영한다
            versionRepository.saveAndFlush(new MedicationScheduleVersion(userId, m.getId(), m.getTimes(), today, now));
        }
    }

    private static List<LocalTime> parseTimes(List<String> times) {
        List<LocalTime> parsed = times.stream().map(TimeOfDay::parse).toList();
        if (new HashSet<>(parsed).size() != parsed.size()) {
            throw ApiException.validation("투약 시각이 중복되었습니다.");
        }
        return parsed;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
