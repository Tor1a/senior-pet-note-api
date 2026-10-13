package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 할 일 API 요청·응답 (docs/api-todos.md). 요청 DTO 에 userId 필드는 두지 않는다(알 수 없는 필드는 무시). */
public final class TodoDtos {

    private TodoDtos() {
    }

    /** POST /api/pets/{petId}/todos, PUT /api/todos/{id} 요청. 길이·범위 검증은 TodoService 가 한다(코드 포인트 기준). */
    public record TodoRequest(
            @NotNull(message = "제목을 입력하세요.")
            String title,
            @NotNull(message = "날짜를 입력하세요.")
            LocalDate dueDate,
            String dueTime,
            String memo,
            String hospitalNote,
            Boolean remind) {
    }

    /** PUT /api/todos/{id}/completion 요청 */
    public record CompletionRequest(
            @NotNull(message = "완료 여부(completed)를 입력하세요.")
            Boolean completed) {
    }

    /** Todo = {id, petId, title, dueDate, dueTime, memo, hospitalNote, remind, completed, completedAt, dateStatus, createdAt, updatedAt} */
    public record TodoResponse(UUID id, UUID petId, String title, LocalDate dueDate, String dueTime, String memo,
                               String hospitalNote, boolean remind, boolean completed, Instant completedAt,
                               String dateStatus, Instant createdAt, Instant updatedAt) {

        public static TodoResponse from(TodoService.TodoView v) {
            Todo t = v.todo();
            return new TodoResponse(t.getId(), t.getPetId(), t.getTitle(), t.getDueDate(),
                    TimeOfDay.format(t.getDueTime()), t.getMemo(), t.getHospitalNote(),
                    t.isRemind(), t.getCompletedAt() != null, t.getCompletedAt(), v.dateStatus(),
                    t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** GET 목록 응답 {recordDate, todos} */
    public record TodoListResponse(LocalDate recordDate, List<TodoResponse> todos) {

        public static TodoListResponse from(TodoService.TodoList l) {
            return new TodoListResponse(l.recordDate(), l.todos().stream().map(TodoResponse::from).toList());
        }
    }
}
