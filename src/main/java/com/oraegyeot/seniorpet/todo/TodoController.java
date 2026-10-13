package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.security.CurrentUserId;
import com.oraegyeot.seniorpet.todo.TodoDtos.CompletionRequest;
import com.oraegyeot.seniorpet.todo.TodoDtos.TodoListResponse;
import com.oraegyeot.seniorpet.todo.TodoDtos.TodoRequest;
import com.oraegyeot.seniorpet.todo.TodoDtos.TodoResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 내 할 일 일정 API (docs/api-todos.md). 사용자 id 는 항상 @CurrentUserId 로만 받는다. */
@RestController
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping("/api/pets/{petId}/todos")
    public TodoListResponse list(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                 @RequestParam(required = false) String status) {
        return TodoListResponse.from(todoService.list(userId, petId, status));
    }

    @PostMapping("/api/pets/{petId}/todos")
    @ResponseStatus(HttpStatus.CREATED)
    public TodoResponse create(@CurrentUserId UUID userId, @PathVariable UUID petId,
                               @Valid @RequestBody TodoRequest req) {
        return TodoResponse.from(todoService.create(userId, petId, req));
    }

    @PutMapping("/api/todos/{id}")
    public TodoResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
                               @Valid @RequestBody TodoRequest req) {
        return TodoResponse.from(todoService.update(userId, id, req));
    }

    @PutMapping("/api/todos/{id}/completion")
    public TodoResponse setCompletion(@CurrentUserId UUID userId, @PathVariable UUID id,
                                      @Valid @RequestBody CompletionRequest req) {
        return TodoResponse.from(todoService.setCompletion(userId, id, req.completed()));
    }

    @DeleteMapping("/api/todos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        todoService.delete(userId, id);
    }
}
