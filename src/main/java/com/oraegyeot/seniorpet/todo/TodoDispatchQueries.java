package com.oraegyeot.seniorpet.todo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 할 일 알림 발송 작업 전용 "시스템 쿼리" — 보안 규칙 9번의 예외(README).
 *
 * 발송은 사용자 요청이 아니라 전 사용자 대상 작업이라 OwnedRepository(userId 필수) 규칙을 적용할 수 없다.
 * 그래서 user_id 조건 없는 쿼리를 이 클래스 한 곳에 모은다.
 * **이 빈은 TodoDispatcher 에서만 주입한다. 컨트롤러·사용자 서비스에서 주입 금지.**
 */
@Component
class TodoDispatchQueries {

    private static final Logger log = LoggerFactory.getLogger(TodoDispatchQueries.class);

    private final JdbcClient jdbc;

    TodoDispatchQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 발송 후보 1건: 알림 켬 + 미완료 할 일 + 반려동물 이름 */
    record Candidate(UUID userId, UUID todoId, UUID petId, String petName, String title, LocalDate dueDate,
                     LocalTime dueTime, Instant updatedAt) {
    }

    /** 예정 날짜가 [fromDate, toDate] 인 알림 켬·미완료 할 일(1차 필터). kind 별 발송 시각 판정은 호출한 쪽이 한다. */
    List<Candidate> findCandidates(LocalDate fromDate, LocalDate toDate) {
        return jdbc.sql("""
                        select t.user_id, t.id as todo_id, t.pet_id, p.name as pet_name, t.title,
                               t.due_date, t.due_time, t.updated_at
                          from todos t
                          join pets p on p.id = t.pet_id and p.user_id = t.user_id
                         where t.remind
                           and t.completed_at is null
                           and t.due_date between :fromDate and :toDate
                         order by t.due_date, t.id
                        """)
                .param("fromDate", fromDate)
                .param("toDate", toDate)
                .query((rs, n) -> toCandidate(rs))
                .list();
    }

    /**
     * 회차 선점. 같은 (할 일, 종류, 예정 날짜)가 이미 있으면(다른 tick·다른 인스턴스가 선점) 빈 값.
     * 트랜잭션 없이 실행하므로 이 문장이 끝나면 바로 커밋된다 → 그 뒤에 발송(최대 1회).
     */
    Optional<Long> claim(UUID userId, UUID todoId, String kind, LocalDate dueDate, Instant fireAt) {
        try {
            return jdbc.sql("""
                            insert into todo_dispatches (user_id, todo_id, kind, due_date, fire_at, status)
                            values (:userId, :todoId, :kind, :dueDate, :fireAt, 'claimed')
                            on conflict on constraint todo_dispatches_once_per_slot do nothing
                            returning id
                            """)
                    .param("userId", userId)
                    .param("todoId", todoId)
                    .param("kind", kind)
                    .param("dueDate", dueDate)
                    .param("fireAt", OffsetDateTime.ofInstant(fireAt, ZoneOffset.UTC))
                    .query(Long.class)
                    .optional();
        } catch (DataIntegrityViolationException e) {
            // 후보 조회 뒤 할 일·사용자가 삭제된 경우(삭제·회원 탈퇴): 외래키 위반(23503)만 건너뛴다(오류 아님).
            // 그 밖의 무결성 오류는 숨기지 않고 다시 던진다(호출한 쪽의 단위 catch 가 error 로그를 남김).
            if (!isForeignKeyViolation(e)) {
                throw e;
            }
            log.debug("할 일 알림 건너뜀(삭제된 할 일): todoId={}", todoId);
            return Optional.empty();
        }
    }

    /**
     * 선점 뒤에도 아직 보낼 상태인가(삭제되지 않았고 미완료, 알림 켬, 예정 날짜가 선점한 날짜와 같음).
     * 선점 직전 재확인은 하지 않는다(경합 시 1회 허용).
     */
    boolean isStillPending(UUID userId, UUID todoId, LocalDate claimedDueDate) {
        return jdbc.sql("""
                        select count(*) from todos
                         where id = :todoId and user_id = :userId and completed_at is null
                           and remind and due_date = :dueDate
                        """)
                .param("dueDate", claimedDueDate)
                .param("todoId", todoId)
                .param("userId", userId)
                .query(Long.class).single() > 0;
    }

    /** 발송 결과 반영 */
    void finish(long dispatchId, String status, int successCount, int failureCount) {
        jdbc.sql("""
                        update todo_dispatches
                           set status = :status, success_count = :success, failure_count = :failure
                         where id = :id
                        """)
                .param("status", status)
                .param("success", successCount)
                .param("failure", failureCount)
                .param("id", dispatchId)
                .update();
    }

    private static boolean isForeignKeyViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Candidate toCandidate(ResultSet rs) throws SQLException {
        return new Candidate(rs.getObject("user_id", UUID.class), rs.getObject("todo_id", UUID.class),
                rs.getObject("pet_id", UUID.class), rs.getString("pet_name"), rs.getString("title"),
                rs.getObject("due_date", LocalDate.class), rs.getObject("due_time", LocalTime.class),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
