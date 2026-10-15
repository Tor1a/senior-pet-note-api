package com.oraegyeot.seniorpet.history;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.dailylog.DailyLog;
import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogResponse;
import com.oraegyeot.seniorpet.dailylog.DailyLogRepository;
import com.oraegyeot.seniorpet.history.HistoryDtos.HistoryDay;
import com.oraegyeot.seniorpet.history.HistoryDtos.HistoryResponse;
import com.oraegyeot.seniorpet.history.HistoryDtos.MedicationCount;
import com.oraegyeot.seniorpet.medication.MedicationScheduleVersion;
import com.oraegyeot.seniorpet.medication.MedicationScheduleVersionRepository;
import com.oraegyeot.seniorpet.medlog.MedLog;
import com.oraegyeot.seniorpet.medlog.MedLogRepository;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지난 기록 조회. 쿼리는 4회 고정(pet 소유 확인, 일정 이력 버전, daily_logs, med_logs).
 * 투약 예정 횟수는 날짜별로 "그 날짜에 유효했던 약 일정(medication_schedule_versions)" 기준이다. 삭제한 약도 삭제 전 날짜에는 센다.
 * 이력을 쓰기 전(V7 백필)의 기간은 현재 일정을 등록일부터 적용한 근사값이라 medicationBasis 는 "current" 로 유지한다.
 */
@Service
public class HistoryService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 90;
    static final String MEDICATION_BASIS = "current";

    private final PetService petService;
    private final MedicationScheduleVersionRepository versionRepository;
    private final MedLogRepository medLogRepository;
    private final DailyLogRepository dailyLogRepository;
    private final RecordDateCalculator recordDates;

    public HistoryService(PetService petService, MedicationScheduleVersionRepository versionRepository,
                          MedLogRepository medLogRepository, DailyLogRepository dailyLogRepository,
                          RecordDateCalculator recordDates) {
        this.petService = petService;
        this.versionRepository = versionRepository;
        this.medLogRepository = medLogRepository;
        this.dailyLogRepository = dailyLogRepository;
        this.recordDates = recordDates;
    }

    @Transactional(readOnly = true)
    public HistoryResponse history(UUID userId, UUID petId, String fromText, String toText) {
        petService.getOwned(userId, petId); // 남의 pet = 404 (파라미터 검증보다 먼저)
        LocalDate current = recordDates.currentRecordDate();
        LocalDate to = toText == null ? current : parse(toText);
        LocalDate from = fromText == null ? to.minusDays(DEFAULT_DAYS - 1) : parse(fromText);
        if (to.isAfter(current)) {
            throw invalid("오늘 이후 날짜는 조회할 수 없어요.");
        }
        if (from.isAfter(to)) {
            throw invalid("시작 날짜는 끝 날짜보다 늦을 수 없어요.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("조회 기간은 최대 90일까지예요.");
        }

        Map<LocalDate, DailyLog> logs = new HashMap<>();
        for (DailyLog d : dailyLogRepository.findAllByPetIdAndUserIdAndRecordDateBetween(petId, userId, from, to)) {
            logs.put(d.getRecordDate(), d);
        }

        List<MedicationScheduleVersion> versions = versionRepository.findOverlappingByUserIdAndPetId(
                userId, petId, from, to);
        Set<String> taken = new HashSet<>();
        if (!versions.isEmpty()) {
            List<UUID> ids = versions.stream().map(MedicationScheduleVersion::getMedicationId).distinct().toList();
            for (MedLog l : medLogRepository.findAllByUserIdAndRecordDateBetweenAndMedicationIdIn(
                    userId, from, to, ids)) {
                taken.add(slotKey(l.getRecordDate(), l.getMedicationId(), l.getScheduledTime()));
            }
        }

        List<HistoryDay> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int scheduled = 0;
            int takenCount = 0;
            for (MedicationScheduleVersion v : versions) {
                if (!v.covers(date)) {
                    continue; // 그 날짜에 유효하지 않은 버전(등록 전·삭제 후·다른 시기 일정)은 뺀다
                }
                for (LocalTime t : v.getTimes()) {
                    scheduled++;
                    if (taken.contains(slotKey(date, v.getMedicationId(), t))) {
                        takenCount++;
                    }
                }
            }
            DailyLog log = logs.get(date);
            days.add(new HistoryDay(date, log == null ? null : DailyLogResponse.from(log),
                    new MedicationCount(scheduled, takenCount)));
        }
        return new HistoryResponse(petId, from, to, current, MEDICATION_BASIS, days);
    }

    private static LocalDate parse(String text) {
        try {
            return LocalDate.parse(text); // ISO yyyy-MM-dd, 없는 날짜(02-30)는 예외
        } catch (DateTimeParseException e) {
            throw invalid("날짜는 YYYY-MM-DD 형식이어야 해요.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", message);
    }

    private static String slotKey(LocalDate date, UUID medicationId, LocalTime time) {
        return date + "@" + medicationId + "@" + time;
    }
}
