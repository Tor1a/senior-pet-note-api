package com.oraegyeot.seniorpet.medication;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.medication.MedicationDtos.MedicationRequest;
import com.oraegyeot.seniorpet.pet.PetService;
import java.time.Clock;
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
    private final Clock clock;

    public MedicationService(MedicationRepository medicationRepository, PetService petService, Clock clock) {
        this.medicationRepository = medicationRepository;
        this.petService = petService;
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

    @Transactional
    public Medication create(UUID userId, UUID petId, MedicationRequest req) {
        petService.getOwned(userId, petId); // 부모 소유부터 검증
        Medication m = new Medication(userId, petId, req.name().trim(), blankToNull(req.doseText()),
                parseTimes(req.times()), clock.instant());
        return medicationRepository.saveAndFlush(m);
    }

    @Transactional
    public Medication update(UUID userId, UUID medicationId, MedicationRequest req) {
        Medication m = getOwnedActive(userId, medicationId);
        m.update(req.name().trim(), blankToNull(req.doseText()), parseTimes(req.times()), clock.instant());
        return medicationRepository.saveAndFlush(m);
    }

    /** 삭제 = 비활성화. 과거 med_logs 는 그대로 남는다. */
    @Transactional
    public void deactivate(UUID userId, UUID medicationId) {
        Medication m = getOwnedActive(userId, medicationId);
        m.deactivate(clock.instant());
        medicationRepository.saveAndFlush(m);
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
