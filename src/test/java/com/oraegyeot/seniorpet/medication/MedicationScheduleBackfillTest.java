package com.oraegyeot.seniorpet.medication;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.common.AppProperties;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V7 백필 SQL 검증. 마이그레이션 파일의 백필 문장을 읽어, 테스트가 만든 약에만 필터를 붙여 다시 실행한다.
 * SQL 에 복제된 시간대(Asia/Seoul)·컷오프(04:00)가 애플리케이션 설정과 어긋나면 이 테스트가 실패한다.
 */
class MedicationScheduleBackfillTest extends ApiTestSupport {

    private static final Path V7 = Path.of("src/main/resources/db/migration/V7__medication_schedule_history.sql");
    private static final String FILTER_MARK = "/* BACKFILL_FILTER */";

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private RecordDateCalculator recordDates;

    private static String backfillSql() throws Exception {
        String sql = Files.readString(V7);
        return sql.substring(sql.indexOf("insert into medication_schedule_versions"));
    }

    @Test
    void SQL에_복제된_시간대와_컷오프가_애플리케이션_설정과_같다() throws Exception {
        String sql = backfillSql();
        assertThat(sql).contains("at time zone '" + AppProperties.DEFAULT_ZONE + "'");
        assertThat(AppProperties.DEFAULT_ZONE).isEqualTo("Asia/Seoul");
        // 이 테스트가 도는 환경의 app.zone 이 백필 SQL 과 같아야 한다(운영 APP_ZONE 은 배포 전 별도 확인)
        assertThat(appProperties.zoneId()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(RecordDateCalculator.CUTOFF.getHour()).isEqualTo(4);
        assertThat(RecordDateCalculator.CUTOFF.getMinute()).isZero();
        assertThat(sql).contains("interval '4 hours'");
    }

    @Test
    void SQL_기록_날짜_환산이_RecordDateCalculator와_샘플_시각에서_일치한다() {
        RecordDateCalculator seoul = new RecordDateCalculator(clock, ZoneId.of("Asia/Seoul"));
        List<LocalDateTime> samples = List.of(
                LocalDateTime.of(2026, 10, 6, 0, 0), LocalDateTime.of(2026, 10, 6, 3, 59),
                LocalDateTime.of(2026, 10, 6, 4, 0), LocalDateTime.of(2026, 10, 6, 23, 59),
                LocalDateTime.of(2026, 1, 1, 3, 59), LocalDateTime.of(2026, 1, 1, 4, 0),
                LocalDateTime.of(2028, 3, 1, 2, 0));
        for (LocalDateTime ldt : samples) {
            Instant at = ldt.atZone(ZoneId.of("Asia/Seoul")).toInstant();
            LocalDate sql = jdbc.sql("select ((cast(:t as timestamptz) at time zone 'Asia/Seoul') - interval '4 hours')::date")
                    .param("t", java.sql.Timestamp.from(at)).query(LocalDate.class).single();
            assertThat(sql).as(ldt.toString()).isEqualTo(seoul.recordDateOf(at));
        }
    }

    private UUID insertMed(UUID userId, UUID petId, boolean active, LocalDateTime created, LocalDateTime updated) {
        UUID id = jdbc.sql("insert into medications (user_id, pet_id, name, times, active, created_at) "
                        + "values (:u, :p, 'x', '{08:00,20:00}', :a, :c) returning id")
                .param("u", userId).param("p", petId).param("a", active)
                .param("c", java.sql.Timestamp.from(created.atZone(SEOUL).toInstant()))
                .query(UUID.class).single();
        // updated_at 은 트리거가 now() 로 덮으므로 트리거를 잠시 끄고 원하는 값을 넣는다
        jdbc.sql("alter table medications disable trigger medications_set_updated_at").update();
        try {
            jdbc.sql("update medications set updated_at = :t where id = :id")
                    .param("t", java.sql.Timestamp.from(updated.atZone(SEOUL).toInstant())).param("id", id).update();
        } finally {
            jdbc.sql("alter table medications enable trigger medications_set_updated_at").update();
        }
        return id;
    }

    @Test
    void 백필은_활성_삭제_등록당일삭제_경계_약을_기대한_범위로_만든다() throws Exception {
        String token = newDisposableUserToken();
        UUID userId = userIdOf(token);
        UUID petId = UUID.fromString(createPet(token));
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 12, 0);
        UUID active = insertMed(userId, petId, true, base, base);
        UUID early = insertMed(userId, petId, true, LocalDateTime.of(2026, 10, 6, 3, 59), base); // 기록 날짜 10-05
        UUID cutoff = insertMed(userId, petId, true, LocalDateTime.of(2026, 10, 6, 4, 0), base); // 10-06
        UUID deleted = insertMed(userId, petId, false, base, LocalDateTime.of(2026, 10, 5, 9, 0)); // ~10-04
        UUID sameDay = insertMed(userId, petId, false, base, LocalDateTime.of(2026, 10, 1, 18, 0)); // 행 없음
        UUID deletedAtDawn = insertMed(userId, petId, false, base, LocalDateTime.of(2026, 10, 5, 3, 59)); // 삭제 날짜 10-04 → ~10-03

        List<UUID> ids = List.of(active, early, cutoff, deleted, sameDay, deletedAtDawn);
        jdbc.sql("delete from medication_schedule_versions where user_id = :u").param("u", userId).update();
        String in = ids.stream().map(i -> "'" + i + "'").reduce((a, b) -> a + "," + b).orElseThrow();
        String sql = backfillSql().replace(FILTER_MARK, "and m.id in (" + in + ")");
        assertThat(sql).doesNotContain(FILTER_MARK);
        jdbc.sql(sql.substring(0, sql.lastIndexOf(';'))).update();

        record R(LocalDate from, LocalDate to) {
        }
        java.util.function.Function<UUID, List<R>> rows = id -> jdbc.sql(
                        "select valid_from, valid_to from medication_schedule_versions where medication_id = :m")
                .param("m", id).query((rs, n) -> new R(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)))
                .list();
        assertThat(rows.apply(active)).containsExactly(new R(LocalDate.of(2026, 10, 1), null));
        assertThat(rows.apply(early)).containsExactly(new R(LocalDate.of(2026, 10, 5), null));
        assertThat(rows.apply(cutoff)).containsExactly(new R(LocalDate.of(2026, 10, 6), null));
        assertThat(rows.apply(deleted)).containsExactly(new R(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4)));
        assertThat(rows.apply(sameDay)).isEmpty();
        assertThat(rows.apply(deletedAtDawn)).containsExactly(new R(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
        // 활성 약은 열린 버전 정확히 1개, 모든 행 valid_to >= valid_from
        List<Integer> open = new ArrayList<>();
        for (UUID id : List.of(active, early, cutoff)) {
            open.add(jdbc.sql("select count(*) from medication_schedule_versions where medication_id = :m and valid_to is null")
                    .param("m", id).query(Integer.class).single());
        }
        assertThat(open).containsExactly(1, 1, 1);
        // 백필 행의 times 는 현재 times 와 같다
        assertThat(jdbc.sql("select count(*) from medication_schedule_versions v join medications m on m.id = v.medication_id "
                        + "where m.user_id = :u and v.times <> m.times").param("u", userId).query(Long.class).single()).isZero();
    }
}
