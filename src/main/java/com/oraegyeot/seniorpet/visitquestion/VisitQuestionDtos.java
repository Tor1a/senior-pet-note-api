package com.oraegyeot.seniorpet.visitquestion;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 진료 질문 API 요청·응답 (docs/api-visit-questions.md). 요청 DTO 에 userId·petId 필드는 두지 않는다(알 수 없는 필드는 무시). */
public final class VisitQuestionDtos {

    private VisitQuestionDtos() {
    }

    /** POST /api/pets/{petId}/visit-questions, PUT /api/visit-questions/{id} 요청. 길이 검증은 서비스가 한다(코드 포인트 기준). */
    public record VisitQuestionRequest(
            @NotNull(message = "물어볼 것을 입력하세요.")
            String text) {

        /** 프레임워크의 TRACE 로그가 본문을 찍어도 글이 남지 않게 한다. */
        @Override
        public String toString() {
            return "VisitQuestionRequest[text=***]";
        }
    }

    /** PUT /api/visit-questions/{id}/completion 요청 */
    public record CompletionRequest(
            @NotNull(message = "완료 여부(completed)를 입력하세요.")
            Boolean completed) {
    }

    /** VisitQuestion = {id, petId, text, completed, completedAt, createdAt, updatedAt} */
    public record VisitQuestionResponse(UUID id, UUID petId, String text, boolean completed, Instant completedAt,
                                        Instant createdAt, Instant updatedAt) {

        /** 프레임워크의 TRACE 로그가 응답을 찍어도 글이 남지 않게 한다. */
        @Override
        public String toString() {
            return "VisitQuestionResponse[id=" + id + "]";
        }

        public static VisitQuestionResponse from(VisitQuestion q) {
            return new VisitQuestionResponse(q.getId(), q.getPetId(), q.getText(), q.getCompletedAt() != null,
                    q.getCompletedAt(), q.getCreatedAt(), q.getUpdatedAt());
        }
    }

    /** GET 목록 응답 {questions} */
    public record VisitQuestionListResponse(List<VisitQuestionResponse> questions) {

        public static VisitQuestionListResponse from(List<VisitQuestion> rows) {
            return new VisitQuestionListResponse(rows.stream().map(VisitQuestionResponse::from).toList());
        }
    }
}
