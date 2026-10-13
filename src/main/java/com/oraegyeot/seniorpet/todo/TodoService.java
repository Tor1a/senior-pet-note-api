package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.todo.TodoDtos.TodoRequest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 할 일 조회·등록·수정·완료·삭제. PetService 와 같은 소유자 검증 패턴(첫 파라미터 userId, 남의 것 404).
 * 반려동물 소유는 petService.getOwned 로 먼저 검증한다. dateStatus 는 RecordDateCalculator 의 현재 기록 날짜와 비교해 서버가 계산한다.
 * 로그에는 제목·메모를 남기지 않는다.
 */
@Service
public class TodoService {

    /** 사용자당 최대 할 일 수(완료 포함) */
    public static final int MAX_PER_USER = 300;
    public static final int TITLE_MAX = 40;
    public static final int MEMO_MAX = 200;
    public static final int HOSPITAL_NOTE_MAX = 500;
    /** 날짜 허용 범위: 현재 기록 날짜 기준 -400일 ~ +730일 */
    public static final int PAST_DAYS = 400;
    public static final int FUTURE_DAYS = 730;

    private final TodoRepository todoRepository;
    private final PetService petService;
    private final RecordDateCalculator recordDates;
    private final EntityManager em;
    private final JdbcClient jdbc;

    public TodoService(TodoRepository todoRepository, PetService petService, RecordDateCalculator recordDates,
                       EntityManager em, JdbcClient jdbc) {
        this.todoRepository = todoRepository;
        this.petService = petService;
        this.recordDates = recordDates;
        this.em = em;
        this.jdbc = jdbc;
    }

    /** 응답 재료: 할 일 + 서버가 계산한 dateStatus */
    public record TodoView(Todo todo, String dateStatus) {
    }

    public record TodoList(LocalDate recordDate, List<TodoView> todos) {
    }

    @Transactional(readOnly = true)
    public TodoList list(UUID userId, UUID petId, String status) {
        boolean done = parseStatus(status);
        petService.getOwned(userId, petId); // 부모 소유부터 검증
        LocalDate today = recordDates.currentRecordDate();
        List<Todo> rows = done
                ? todoRepository.findTop100ByUserIdAndPetIdAndCompletedAtIsNotNullOrderByCompletedAtDesc(userId, petId)
                : todoRepository.findOpenByUserId(userId, petId);
        return new TodoList(today, rows.stream().map(t -> new TodoView(t, dateStatus(t.getDueDate(), today))).toList());
    }

    @Transactional
    public TodoView create(UUID userId, UUID petId, TodoRequest req) {
        petService.getOwned(userId, petId);
        LocalDate today = recordDates.currentRecordDate();
        Input in = validate(req, today);
        // 같은 사용자의 동시 등록으로 한도를 넘지 않게 직렬화한다(트랜잭션 끝까지 유지되는 advisory lock)
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "todos:" + userId)
                .query().singleRow();
        if (todoRepository.countByUserId(userId) >= MAX_PER_USER) {
            throw new ApiException(HttpStatus.CONFLICT, "TODO_LIMIT_REACHED",
                    "할 일은 " + MAX_PER_USER + "개까지 등록할 수 있어요. 지난 할 일을 삭제한 뒤 다시 시도해 주세요.");
        }
        Todo todo = new Todo(userId, petId, in.title(), in.dueDate(), in.dueTime(), in.memo(), in.hospitalNote(),
                in.remind(), recordDates.now());
        return view(saveAndRefresh(todo), today);
    }

    @Transactional
    public TodoView update(UUID userId, UUID id, TodoRequest req) {
        Todo todo = getOwned(userId, id);
        LocalDate today = recordDates.currentRecordDate();
        Input in = validate(req, today);
        todo.replace(in.title(), in.dueDate(), in.dueTime(), in.memo(), in.hospitalNote(), in.remind(),
                recordDates.now());
        return view(saveAndRefresh(todo), today);
    }

    @Transactional
    public TodoView setCompletion(UUID userId, UUID id, boolean completed) {
        Todo todo = getOwned(userId, id);
        Instant now = recordDates.now();
        if (completed) {
            todo.complete(now);
        } else {
            todo.reopen(now);
        }
        return view(saveAndRefresh(todo), recordDates.currentRecordDate());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        todoRepository.delete(getOwned(userId, id));
    }

    @Transactional(readOnly = true)
    public Todo getOwned(UUID userId, UUID id) {
        return todoRepository.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /** "open"(생략 포함) → false, "done" → true, 그 밖 → 400 */
    private static boolean parseStatus(String status) {
        if (status == null || status.equals("open")) {
            return false;
        }
        if (status.equals("done")) {
            return true;
        }
        throw ApiException.validation("status 는 open 또는 done 이어야 합니다.");
    }

    /** past | today | tomorrow | future (기록 날짜 기준) */
    static String dateStatus(LocalDate dueDate, LocalDate today) {
        if (dueDate.isBefore(today)) {
            return "past";
        }
        if (dueDate.equals(today)) {
            return "today";
        }
        return dueDate.equals(today.plusDays(1)) ? "tomorrow" : "future";
    }

    private TodoView view(Todo todo, LocalDate today) {
        return new TodoView(todo, dateStatus(todo.getDueDate(), today));
    }

    /** updated_at 은 UPDATE 때 DB 트리거가 now() 로 덮어쓴다. 응답이 저장된 값과 같도록 flush 뒤 다시 읽는다. */
    private Todo saveAndRefresh(Todo todo) {
        todoRepository.saveAndFlush(todo);
        em.refresh(todo);
        return todo;
    }

    private record Input(String title, LocalDate dueDate, LocalTime dueTime, String memo, String hospitalNote,
                         boolean remind) {
    }

    /** DTO 어노테이션으로 못 거르는 규칙(코드 포인트 길이, 날짜 범위, 시각 형식). 길이는 DB CHECK(char_length)와 같다. */
    private Input validate(TodoRequest req, LocalDate today) {
        String title = req.title().strip();
        int titleLen = title.codePointCount(0, title.length());
        if (titleLen < 1 || titleLen > TITLE_MAX) {
            throw ApiException.validation("제목은 1~" + TITLE_MAX + "자여야 합니다.");
        }
        if (req.dueDate().isBefore(today.minusDays(PAST_DAYS)) || req.dueDate().isAfter(today.plusDays(FUTURE_DAYS))) {
            throw ApiException.validation("날짜는 오늘 기준 " + PAST_DAYS + "일 전부터 " + FUTURE_DAYS + "일 후까지 입력할 수 있습니다.");
        }
        LocalTime dueTime = req.dueTime() == null ? null : TimeOfDay.parse(req.dueTime());
        String memo = blankToNull(req.memo());
        if (memo != null && memo.codePointCount(0, memo.length()) > MEMO_MAX) {
            throw ApiException.validation("메모는 " + MEMO_MAX + "자 이하여야 합니다.");
        }
        String hospitalNote = blankToNull(req.hospitalNote());
        if (hospitalNote != null && hospitalNote.codePointCount(0, hospitalNote.length()) > HOSPITAL_NOTE_MAX) {
            throw ApiException.validation("병원 메모는 " + HOSPITAL_NOTE_MAX + "자 이하여야 합니다.");
        }
        return new Input(title, req.dueDate(), dueTime, memo, hospitalNote,
                req.remind() == null || req.remind());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
