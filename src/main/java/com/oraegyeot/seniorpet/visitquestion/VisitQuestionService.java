package com.oraegyeot.seniorpet.visitquestion;

import com.oraegyeot.seniorpet.common.ApiException;
import com.oraegyeot.seniorpet.pet.PetService;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.visitquestion.VisitQuestionDtos.VisitQuestionRequest;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진료 질문 조회·등록·수정·완료·삭제. TodoService 와 같은 소유자 검증 패턴(첫 파라미터 userId, 남의 것 404).
 * 반려동물 소유는 petService.getOwned 로 먼저 검증한다.
 * 서버는 text 를 저장·반환만 한다(앞뒤 공백 제거 외 변형·해석 없음).
 * 로그·예외 메시지·응답에 text 를 남기지 않는다.
 */
@Service
public class VisitQuestionService {

    /** 사용자당 최대 개수(완료 포함) */
    public static final int MAX_PER_USER = 30;
    public static final int TEXT_MAX = 100;

    private final VisitQuestionRepository repository;
    private final PetService petService;
    private final RecordDateCalculator recordDates;
    private final EntityManager em;
    private final JdbcClient jdbc;

    public VisitQuestionService(VisitQuestionRepository repository, PetService petService,
                                RecordDateCalculator recordDates, EntityManager em, JdbcClient jdbc) {
        this.repository = repository;
        this.petService = petService;
        this.recordDates = recordDates;
        this.em = em;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<VisitQuestion> list(UUID userId, UUID petId, String status) {
        String s = status == null ? "open" : status;
        if (!s.equals("open") && !s.equals("done") && !s.equals("all")) {
            throw ApiException.validation("status 는 open, done, all 중 하나여야 합니다.");
        }
        petService.getOwned(userId, petId); // 부모 소유부터 검증
        List<VisitQuestion> rows = new ArrayList<>();
        if (!s.equals("done")) {
            rows.addAll(repository.findByUserIdAndPetIdAndCompletedAtIsNullOrderByCreatedAtAscIdAsc(userId, petId));
        }
        if (!s.equals("open")) {
            rows.addAll(repository.findByUserIdAndPetIdAndCompletedAtIsNotNullOrderByCompletedAtDescIdAsc(userId, petId));
        }
        return rows;
    }

    @Transactional
    public VisitQuestion create(UUID userId, UUID petId, VisitQuestionRequest req) {
        petService.getOwned(userId, petId);
        String text = validate(req);
        // 같은 사용자의 동시 등록으로 한도를 넘지 않게 직렬화한다(트랜잭션 끝까지 유지되는 advisory lock)
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "visit_questions:" + userId).query().singleRow();
        if (repository.countByUserId(userId) >= MAX_PER_USER) {
            throw new ApiException(HttpStatus.CONFLICT, "VISIT_QUESTION_LIMIT_REACHED",
                    "물어볼 것은 " + MAX_PER_USER + "개까지 적어 둘 수 있어요. 지난 것을 지운 뒤 다시 시도해 주세요.");
        }
        return saveAndRefresh(new VisitQuestion(userId, petId, text, recordDates.now()));
    }

    @Transactional
    public VisitQuestion update(UUID userId, UUID id, VisitQuestionRequest req) {
        VisitQuestion q = getOwned(userId, id);
        q.replace(validate(req), recordDates.now());
        return saveAndRefresh(q);
    }

    @Transactional
    public VisitQuestion setCompletion(UUID userId, UUID id, boolean completed) {
        VisitQuestion q = getOwned(userId, id);
        if (completed) {
            q.complete(recordDates.now());
        } else {
            q.reopen(recordDates.now());
        }
        return saveAndRefresh(q);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repository.delete(getOwned(userId, id));
    }

    @Transactional(readOnly = true)
    public VisitQuestion getOwned(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /** updated_at 은 UPDATE 때 DB 트리거가 now() 로 덮어쓴다. 응답이 저장된 값과 같도록 flush 뒤 다시 읽는다. */
    private VisitQuestion saveAndRefresh(VisitQuestion q) {
        repository.saveAndFlush(q);
        em.refresh(q);
        return q;
    }

    /** 코드 포인트 길이 검증(DB CHECK char_length 와 같은 기준). 오류 메시지에 입력값을 싣지 않는다. */
    private static String validate(VisitQuestionRequest req) {
        String text = req.text().strip();
        // PostgreSQL text 는 NUL 문자를 저장하지 못해 500 이 되므로 먼저 400 으로 막는다(입력 에코 금지)
        if (text.indexOf('\u0000') >= 0) {
            throw ApiException.validation("물어볼 것에 사용할 수 없는 문자가 있습니다.");
        }
        int len = text.codePointCount(0, text.length());
        if (len < 1 || len > TEXT_MAX) {
            throw ApiException.validation("물어볼 것은 1~" + TEXT_MAX + "자여야 합니다.");
        }
        return text;
    }
}
