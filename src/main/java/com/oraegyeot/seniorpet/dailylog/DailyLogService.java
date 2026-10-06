package com.oraegyeot.seniorpet.dailylog;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.dailylog.DailyLogDtos.DailyLogRequest;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일일 기록 upsert. 날짜는 서버가 계산한 현재 기록 날짜만 허용한다(지난 날짜 수정은 MVP 범위 밖).
 */
@Service
public class DailyLogService {

    private static final BigDecimal MAX_WEIGHT = new BigDecimal("200");

    private final DailyLogRepository dailyLogRepository;
    private final PetService petService;
    private final RecordDateCalculator recordDates;
    private final TransactionTemplate tx;

    public DailyLogService(DailyLogRepository dailyLogRepository, PetService petService,
                           RecordDateCalculator recordDates, PlatformTransactionManager txManager) {
        this.dailyLogRepository = dailyLogRepository;
        this.petService = petService;
        this.recordDates = recordDates;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 검증을 마친 저장 값 */
    private record Values(Short foodLevel, Short waterLevel, Integer waterMl, BigDecimal weightKg,
                          List<String> symptoms, boolean symptomsNone, String symptomOther, String memo) {
    }

    public DailyLog upsert(UUID userId, UUID petId, String recordDateText, DailyLogRequest req) {
        petService.getOwned(userId, petId); // 부모 소유부터 검증(남의 pet = 404)
        LocalDate recordDate = checkRecordDate(recordDateText);
        Values v = validate(req);
        try {
            return tx.execute(s -> save(userId, petId, recordDate, v));
        } catch (DataIntegrityViolationException e) {
            // 같은 날 첫 저장이 동시에 두 번 들어와 unique 제약에 걸린 경우: 이미 생긴 행을 갱신한다
            return tx.execute(s -> save(userId, petId, recordDate, v));
        }
    }

    private DailyLog save(UUID userId, UUID petId, LocalDate recordDate, Values v) {
        DailyLog log = dailyLogRepository.findByPetIdAndUserIdAndRecordDate(petId, userId, recordDate)
                .orElseGet(() -> new DailyLog(userId, petId, recordDate, recordDates.now()));
        log.replace(v.foodLevel(), v.waterLevel(), v.waterMl(), v.weightKg(), v.symptoms(),
                v.symptomsNone(), v.symptomOther(), v.memo(), recordDates.now());
        return dailyLogRepository.saveAndFlush(log);
    }

    /** 경로의 날짜가 서버의 현재 기록 날짜와 같아야 한다. 형식 오류도 같은 코드로 응답한다. */
    private LocalDate checkRecordDate(String text) {
        LocalDate current = recordDates.currentRecordDate();
        LocalDate requested;
        try {
            requested = LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw invalidRecordDate(current);
        }
        if (!requested.equals(current)) {
            throw invalidRecordDate(current);
        }
        return requested;
    }

    /** DTO 어노테이션으로 못 거르는 규칙(증상 조합, 체중 자릿수) 검증 */
    private static Values validate(DailyLogRequest req) {
        List<String> symptoms = req.symptoms() == null
                ? List.of() : new ArrayList<>(new LinkedHashSet<>(req.symptoms())); // 중복 제거, 순서 유지
        boolean none = Boolean.TRUE.equals(req.symptomsNone());
        if (none && !symptoms.isEmpty()) {
            throw ApiException.validation("'특이사항 없음'과 증상을 함께 선택할 수 없습니다.");
        }
        String other = (req.symptomOther() == null || req.symptomOther().isBlank()) ? null : req.symptomOther().trim();
        if (other != null && !symptoms.contains("other")) {
            throw ApiException.validation("기타 증상 내용은 '기타'를 선택했을 때만 입력할 수 있습니다.");
        }
        BigDecimal weight = req.weightKg() == null ? null : req.weightKg().setScale(2, RoundingMode.HALF_UP);
        if (weight != null && (weight.signum() <= 0 || weight.compareTo(MAX_WEIGHT) >= 0)) {
            throw ApiException.validation("체중은 0kg 초과 200kg 미만이어야 합니다.");
        }
        return new Values(toShort(req.foodLevel()), toShort(req.waterLevel()), req.waterMl(), weight,
                symptoms, none, other, req.memo());
    }

    private static Short toShort(Integer i) {
        return i == null ? null : i.shortValue();
    }

    private static ApiException invalidRecordDate(LocalDate current) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECORD_DATE",
                "오늘 기록만 저장할 수 있습니다(현재 기록 날짜: " + current + ").");
    }
}
