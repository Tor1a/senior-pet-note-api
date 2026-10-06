package com.oraegyeot.seniorpet.today;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.today.SuggestionService.DaySample;
import com.oraegyeot.seniorpet.today.SuggestionService.Suggestions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 제안값 계산 단위 테스트 (docs/api-today.md 0-3). DB 없이 실행된다. */
class SuggestionServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private final SuggestionService service = new SuggestionService();

    private static DaySample day(int daysAgo, Integer food, Integer water, Integer ml, String kg) {
        return new DaySample(TODAY.minusDays(daysAgo), food, water, ml, kg == null ? null : new BigDecimal(kg));
    }

    @Test
    void 빈_데이터면_모두_null() {
        Suggestions s = service.suggest(TODAY, List.of());
        assertThat(s).isEqualTo(new Suggestions(null, null, null, null));
    }

    @Test
    void 항목마다_값이_있는_날만_평균() {
        // 식사는 3일치(1,2,3 → 2), 음수 단계는 1일치(3), ml 은 2일치(300, 401 → 350.5 → 351), 체중은 없음
        Suggestions s = service.suggest(TODAY, List.of(
                day(1, 1, null, 300, null),
                day(2, 2, 3, null, null),
                day(3, 3, null, 401, null)));
        assertThat(s.foodLevel()).isEqualTo(2);
        assertThat(s.waterLevel()).isEqualTo(3);
        assertThat(s.waterMl()).isEqualTo(351);
        assertThat(s.weightKg()).isNull();
    }

    @Test
    void 반올림_경계_1_5는_2() {
        Suggestions s = service.suggest(TODAY, List.of(day(1, 1, 1, null, null), day(2, 2, 2, null, null)));
        assertThat(s.foodLevel()).isEqualTo(2);
        assertThat(s.waterLevel()).isEqualTo(2);
    }

    @Test
    void 반올림_경계_2_5는_3() {
        Suggestions s = service.suggest(TODAY, List.of(day(1, 2, 3, null, null), day(2, 3, 2, null, null)));
        assertThat(s.foodLevel()).isEqualTo(3);
        assertThat(s.waterLevel()).isEqualTo(3);
    }

    @Test
    void 체중은_소수_둘째자리_반올림() {
        // (4.30 + 4.41) / 2 = 4.355 → 4.36
        Suggestions s = service.suggest(TODAY, List.of(day(1, null, null, null, "4.30"),
                day(5, null, null, null, "4.41")));
        assertThat(s.weightKg()).isEqualByComparingTo("4.36");
        assertThat(s.weightKg().scale()).isEqualTo(2);
    }

    @Test
    void 칠일_범위_밖은_제외() {
        // 7일 전은 포함, 8일 전은 제외
        Suggestions s = service.suggest(TODAY, List.of(day(7, 1, null, 100, "4.00"),
                day(8, 3, 3, 900, "9.00")));
        assertThat(s.foodLevel()).isEqualTo(1);
        assertThat(s.waterLevel()).isNull();
        assertThat(s.waterMl()).isEqualTo(100);
        assertThat(s.weightKg()).isEqualByComparingTo("4.00");
    }

    @Test
    void 오늘_기록은_제외() {
        Suggestions s = service.suggest(TODAY, List.of(day(0, 3, 3, 500, "5.00"), day(1, 1, null, null, null)));
        assertThat(s.foodLevel()).isEqualTo(1);
        assertThat(s.waterLevel()).isNull();
        assertThat(s.waterMl()).isNull();
        assertThat(s.weightKg()).isNull();
    }
}
