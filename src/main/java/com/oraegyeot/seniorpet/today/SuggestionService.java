package com.oraegyeot.seniorpet.today;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * 제안값(미리 채우기) 계산 — docs/api-today.md 0-3, MVP 세부 결정 5.
 *
 * - 대상: recordDate 를 뺀 직전 7일(recordDate-7 ~ recordDate-1). 범위 밖·당일 기록은 무시한다.
 * - 항목마다 값이 있는 날만 평균을 낸다(값이 없는 날을 0 으로 치지 않는다).
 * - foodLevel/waterLevel/waterMl: 정수로 반올림(HALF_UP). weightKg: 소수 둘째 자리(HALF_UP).
 * - 값이 하나도 없으면 null.
 * DB 를 모르는 순수 계산이라 단위 테스트로 검증한다(SuggestionServiceTest).
 */
@Service
public class SuggestionService {

    /** 평균을 낼 직전 일수 */
    public static final int WINDOW_DAYS = 7;

    /** 하루치 입력 값(없으면 null) */
    public record DaySample(LocalDate recordDate, Integer foodLevel, Integer waterLevel, Integer waterMl,
                            BigDecimal weightKg) {
    }

    /** 계산 결과 */
    public record Suggestions(Integer foodLevel, Integer waterLevel, Integer waterMl, BigDecimal weightKg) {
    }

    public Suggestions suggest(LocalDate recordDate, List<DaySample> samples) {
        LocalDate from = recordDate.minusDays(WINDOW_DAYS);
        LocalDate to = recordDate.minusDays(1);
        List<DaySample> window = samples.stream()
                .filter(s -> !s.recordDate().isBefore(from) && !s.recordDate().isAfter(to))
                .toList();
        BigDecimal weight = average(window, DaySample::weightKg, 2);
        return new Suggestions(
                toInt(average(window, s -> toDecimal(s.foodLevel()), 0)),
                toInt(average(window, s -> toDecimal(s.waterLevel()), 0)),
                toInt(average(window, s -> toDecimal(s.waterMl()), 0)),
                weight);
    }

    /** 값이 있는 날만 평균, scale 자리에서 HALF_UP. 값이 없으면 null */
    private static BigDecimal average(List<DaySample> window, Function<DaySample, BigDecimal> field, int scale) {
        List<BigDecimal> values = window.stream().map(field).filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal toDecimal(Integer i) {
        return i == null ? null : BigDecimal.valueOf(i);
    }

    private static Integer toInt(BigDecimal d) {
        return d == null ? null : d.intValueExact();
    }
}
