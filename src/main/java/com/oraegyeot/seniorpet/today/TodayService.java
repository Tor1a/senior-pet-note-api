package com.oraegyeot.seniorpet.today;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.dailylog.DailyLog;
import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogResponse;
import com.oraegyeot.seniorpet.dailylog.DailyLogRepository;
import com.oraegyeot.seniorpet.medication.Medication;
import com.oraegyeot.seniorpet.medication.MedicationService;
import com.oraegyeot.seniorpet.medlog.MedLog;
import com.oraegyeot.seniorpet.medlog.MedLogRepository;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.today.SuggestionService.DaySample;
import com.oraegyeot.seniorpet.today.SuggestionService.Suggestions;
import com.oraegyeot.seniorpet.today.TodayDtos.Dose;
import com.oraegyeot.seniorpet.today.TodayDtos.LastWeight;
import com.oraegyeot.seniorpet.today.TodayDtos.SuggestionsResponse;
import com.oraegyeot.seniorpet.today.TodayDtos.TodayResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "오늘" 화면 한 번에 조회. 날짜·제안값 계산은 모두 서버가 한다. */
@Service
public class TodayService {

    private final PetService petService;
    private final MedicationService medicationService;
    private final MedLogRepository medLogRepository;
    private final DailyLogRepository dailyLogRepository;
    private final SuggestionService suggestionService;
    private final RecordDateCalculator recordDates;

    public TodayService(PetService petService, MedicationService medicationService,
                        MedLogRepository medLogRepository, DailyLogRepository dailyLogRepository,
                        SuggestionService suggestionService, RecordDateCalculator recordDates) {
        this.petService = petService;
        this.medicationService = medicationService;
        this.medLogRepository = medLogRepository;
        this.dailyLogRepository = dailyLogRepository;
        this.suggestionService = suggestionService;
        this.recordDates = recordDates;
    }

    @Transactional(readOnly = true)
    public TodayResponse today(UUID userId, UUID petId) {
        petService.getOwned(userId, petId); // 남의 pet = 404
        LocalDate recordDate = recordDates.currentRecordDate();

        // 1) 투약 회차: 활성 약의 times 를 풀어서 시각 오름차순
        List<Medication> meds = medicationService.listActive(userId, petId);
        Map<String, MedLog> logs = new HashMap<>();
        if (!meds.isEmpty()) {
            List<UUID> ids = meds.stream().map(Medication::getId).toList();
            for (MedLog l : medLogRepository.findAllByUserIdAndRecordDateAndMedicationIdIn(userId, recordDate, ids)) {
                logs.put(slotKey(l.getMedicationId(), l.getScheduledTime()), l);
            }
        }
        List<Dose> doses = new ArrayList<>();
        for (Medication m : meds) {
            for (LocalTime t : m.getTimes()) {
                MedLog l = logs.get(slotKey(m.getId(), t));
                doses.add(new Dose(m.getId(), m.getName(), m.getDoseText(), TimeOfDay.format(t),
                        l != null, l == null ? null : l.getId(), l == null ? null : l.getTakenAt()));
            }
        }
        doses.sort(Comparator.comparing(Dose::scheduledTime)); // 안정 정렬: 같은 시각은 약 등록 순

        // 2) 그날 일일 기록
        DailyLogResponse dailyLog = dailyLogRepository.findByPetIdAndUserIdAndRecordDate(petId, userId, recordDate)
                .map(DailyLogResponse::from).orElse(null);

        // 3) 제안값: 직전 7일
        List<DaySample> samples = dailyLogRepository.findAllByPetIdAndUserIdAndRecordDateBetween(
                        petId, userId, recordDate.minusDays(SuggestionService.WINDOW_DAYS), recordDate.minusDays(1))
                .stream().map(TodayService::toSample).toList();
        Suggestions s = suggestionService.suggest(recordDate, samples);
        SuggestionsResponse suggestions = new SuggestionsResponse(s.foodLevel(), s.waterLevel(), s.waterMl(),
                s.weightKg() == null ? null : s.weightKg().doubleValue());

        // 4) 직전 체중
        LastWeight lastWeight = dailyLogRepository
                .findFirstByPetIdAndUserIdAndRecordDateBeforeAndWeightKgIsNotNullOrderByRecordDateDesc(
                        petId, userId, recordDate)
                .map(d -> new LastWeight(d.getWeightKg().doubleValue(), d.getRecordDate()))
                .orElse(null);

        return new TodayResponse(recordDate, RecordDateCalculator.CUTOFF_NOTICE, doses, dailyLog,
                suggestions, lastWeight);
    }

    private static String slotKey(UUID medicationId, LocalTime time) {
        return medicationId + "@" + time;
    }

    private static DaySample toSample(DailyLog d) {
        return new DaySample(d.getRecordDate(),
                d.getFoodLevel() == null ? null : d.getFoodLevel().intValue(),
                d.getWaterLevel() == null ? null : d.getWaterLevel().intValue(),
                d.getWaterMl(), d.getWeightKg());
    }
}
