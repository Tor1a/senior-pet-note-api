package com.oraegyeot.seniorpet.visitquestion;

import com.oraegyeot.seniorpet.security.CurrentUserId;
import com.oraegyeot.seniorpet.visitquestion.VisitQuestionDtos.CompletionRequest;
import com.oraegyeot.seniorpet.visitquestion.VisitQuestionDtos.VisitQuestionListResponse;
import com.oraegyeot.seniorpet.visitquestion.VisitQuestionDtos.VisitQuestionRequest;
import com.oraegyeot.seniorpet.visitquestion.VisitQuestionDtos.VisitQuestionResponse;
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

/** 진료 때 물어볼 것 API (docs/api-visit-questions.md). 사용자 id 는 항상 @CurrentUserId 로만 받는다. */
@RestController
public class VisitQuestionController {

    private final VisitQuestionService service;

    public VisitQuestionController(VisitQuestionService service) {
        this.service = service;
    }

    @GetMapping("/api/pets/{petId}/visit-questions")
    public VisitQuestionListResponse list(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                          @RequestParam(required = false) String status) {
        return VisitQuestionListResponse.from(service.list(userId, petId, status));
    }

    @PostMapping("/api/pets/{petId}/visit-questions")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitQuestionResponse create(@CurrentUserId UUID userId, @PathVariable UUID petId,
                                        @Valid @RequestBody VisitQuestionRequest req) {
        return VisitQuestionResponse.from(service.create(userId, petId, req));
    }

    @PutMapping("/api/visit-questions/{id}")
    public VisitQuestionResponse update(@CurrentUserId UUID userId, @PathVariable UUID id,
                                        @Valid @RequestBody VisitQuestionRequest req) {
        return VisitQuestionResponse.from(service.update(userId, id, req));
    }

    @PutMapping("/api/visit-questions/{id}/completion")
    public VisitQuestionResponse setCompletion(@CurrentUserId UUID userId, @PathVariable UUID id,
                                               @Valid @RequestBody CompletionRequest req) {
        return VisitQuestionResponse.from(service.setCompletion(userId, id, req.completed()));
    }

    @DeleteMapping("/api/visit-questions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        service.delete(userId, id);
    }
}
