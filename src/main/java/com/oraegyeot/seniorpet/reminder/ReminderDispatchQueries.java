package com.oraegyeot.seniorpet.reminder;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 알림 발송 작업 전용 "시스템 쿼리" — 보안 규칙 9번의 예외(README).
 *
 * 발송은 사용자 요청이 아니라 전 사용자 대상 작업이라 OwnedRepository(userId 필수) 규칙을 적용할 수 없다.
 * 그래서 user_id 조건 없는 쿼리를 이 클래스 한 곳에 모은다.
 * **이 빈은 ReminderDispatcher 에서만 주입한다. 컨트롤러·사용자 서비스에서 주입 금지.**
 */
@Component
class ReminderDispatchQueries {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchQueries.class);

    private final JdbcClient jdbc;

    ReminderDispatchQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 발송 후보 1건: 켜진 규칙 + 활성 약 + 반려동물 이름 */
    record Candidate(UUID userId, UUID medicationId, UUID petId, String petName, String medicationName,
                            String doseText, List<LocalTime> times, ReminderRule rule,
                            Instant reminderUpdatedAt, Instant medicationUpdatedAt) {
    }

    /**
     * 투약 시각 중 하나라도 slotTimes 에 들어 있는, 켜진 규칙 + 활성 약 목록(1차 필터).
     * 요일·간격·시작/종료일 판정은 호출한 쪽이 ReminderRule 로 한다.
     */
    List<Candidate> findCandidates(Collection<LocalTime> slotTimes) {
        if (slotTimes.isEmpty()) {
            return List.of();
        }
        // time[] 배열 리터럴 '{08:00:00,08:01:00}' 로 넘긴다(드라이버의 배열 생성 없이)
        String timesLiteral = slotTimes.stream().map(LocalTime::toString)
                .collect(Collectors.joining(",", "{", "}"));
        return jdbc.sql("""
                        select r.user_id, r.medication_id, m.pet_id, p.name as pet_name,
                               m.name as medication_name, m.dose_text,
                               cast(m.times as text[]) as times,
                               r.enabled, r.repeat_type, cast(r.days_of_week as int[]) as days_of_week,
                               r.interval_days, r.start_date, r.end_date,
                               r.updated_at as reminder_updated_at, m.updated_at as medication_updated_at
                          from medication_reminders r
                          join medications m on m.id = r.medication_id and m.user_id = r.user_id
                          join pets p on p.id = m.pet_id and p.user_id = m.user_id
                         where r.enabled
                           and m.active
                           and m.times && cast(:times as time[])
                         order by r.medication_id
                        """)
                .param("times", timesLiteral)
                .query((rs, n) -> toCandidate(rs))
                .list();
    }

    /**
     * 회차 선점. 같은 회차가 이미 있으면(다른 tick·다른 인스턴스가 선점) 빈 값.
     * 트랜잭션 없이 실행하므로 이 문장이 끝나면 바로 커밋된다 → 그 뒤에 발송(최대 1회).
     */
    Optional<Long> claim(UUID userId, UUID medicationId, LocalDate recordDate, LocalTime scheduledTime,
                                Instant fireAt) {
        try {
            return insertClaim(userId, medicationId, recordDate, scheduledTime, fireAt);
        } catch (DataIntegrityViolationException e) {
            // 후보 조회 뒤 약·사용자가 삭제된 경우(회원 탈퇴 등): 외래키 위반(23503)만 건너뛴다(오류 아님).
            // 그 밖의 무결성 오류는 숨기지 않고 다시 던진다(호출한 쪽의 회차 단위 catch 가 error 로그를 남김).
            if (!isForeignKeyViolation(e)) {
                throw e;
            }
            log.debug("투약 알림 회차 건너뜀(삭제된 약): medicationId={}", medicationId);
            return Optional.empty();
        }
    }

    private static boolean isForeignKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private Optional<Long> insertClaim(UUID userId, UUID medicationId, LocalDate recordDate,
                                       LocalTime scheduledTime, Instant fireAt) {
        return jdbc.sql("""
                        insert into reminder_dispatches
                               (user_id, medication_id, record_date, scheduled_time, fire_at, status)
                        values (:userId, :medicationId, :recordDate, :scheduledTime, :fireAt, 'claimed')
                        on conflict on constraint reminder_dispatches_once_per_slot do nothing
                        returning id
                        """)
                .param("userId", userId)
                .param("medicationId", medicationId)
                .param("recordDate", recordDate)
                .param("scheduledTime", scheduledTime)
                .param("fireAt", OffsetDateTime.ofInstant(fireAt, ZoneOffset.UTC))
                .query(Long.class)
                .optional();
    }

    /** 발송 결과 반영 */
    void finish(long dispatchId, String status, int successCount, int failureCount) {
        jdbc.sql("""
                        update reminder_dispatches
                           set status = :status, success_count = :success, failure_count = :failure
                         where id = :id
                        """)
                .param("status", status)
                .param("success", successCount)
                .param("failure", failureCount)
                .param("id", dispatchId)
                .update();
    }

    private static Candidate toCandidate(ResultSet rs) throws SQLException {
        List<LocalTime> times = Arrays.stream(stringArray(rs.getArray("times")))
                .map(LocalTime::parse).sorted().toList();
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        Array daysArray = rs.getArray("days_of_week");
        if (daysArray != null) {
            for (Object d : (Object[]) daysArray.getArray()) {
                days.add(DayOfWeek.of(((Number) d).intValue()));
            }
        }
        Number interval = (Number) rs.getObject("interval_days");
        ReminderRule rule = new ReminderRule(rs.getBoolean("enabled"), RepeatType.fromCode(rs.getString("repeat_type")),
                days, interval == null ? null : interval.intValue(),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class));
        return new Candidate(rs.getObject("user_id", UUID.class), rs.getObject("medication_id", UUID.class),
                rs.getObject("pet_id", UUID.class), rs.getString("pet_name"), rs.getString("medication_name"),
                rs.getString("dose_text"), times, rule,
                rs.getObject("reminder_updated_at", OffsetDateTime.class).toInstant(),
                rs.getObject("medication_updated_at", OffsetDateTime.class).toInstant());
    }

    private static String[] stringArray(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(String::valueOf).toArray(String[]::new);
    }
}
