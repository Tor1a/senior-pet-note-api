package com.oraegyeot.seniorpet.visitquestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** V8 마이그레이션의 CHECK·복합 외래키·cascade: API 를 거치지 않은 잘못된 값도 DB 가 막는다. */
class VisitQuestionSchemaTest extends ApiTestSupport {

    private UUID userId;
    private UUID petId;

    @BeforeEach
    void setUp() throws Exception {
        String token = newDisposableUserToken();
        userId = userIdOf(token);
        petId = UUID.fromString(createPet(token));
    }

    private UUID insert(UUID user, UUID pet, String text) {
        return jdbc.sql("insert into visit_questions (user_id, pet_id, text) values (:u, :p, :t) returning id")
                .param("u", user).param("p", pet).param("t", text).query(UUID.class).single();
    }

    @Test
    void 글은_공백_제외_1자_이상_100자_이하() {
        assertThatThrownBy(() -> insert(userId, petId, "")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(userId, petId, "   ")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(userId, petId, "가".repeat(101))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insert(userId, petId, "가".repeat(100))).doesNotThrowAnyException();
        assertThatCode(() -> insert(userId, petId, "😀".repeat(100))).doesNotThrowAnyException();
    }

    @Test
    void 남의_반려동물에는_붙일_수_없다() throws Exception {
        String other = newDisposableUserToken();
        UUID otherPet = UUID.fromString(createPet(other));
        assertThatThrownBy(() -> insert(userId, otherPet, "침입")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 반려동물_행을_지우면_질문도_지워진다() {
        UUID id = insert(userId, petId, "질문");
        jdbc.sql("delete from pets where id = :p").param("p", petId).update();
        assertThat(jdbc.sql("select count(*) from visit_questions where id = :id").param("id", id)
                .query(Long.class).single()).isZero();
    }

    @Test
    void 사용자_행을_지우면_질문도_지워진다() throws Exception {
        String t = newUserToken();
        UUID u = userIdOf(t);
        registerForCleanup(u);
        UUID p = UUID.fromString(createPet(t));
        insert(u, p, "질문");
        jdbc.sql("delete from users where id = :u").param("u", u).update();
        assertThat(jdbc.sql("select count(*) from visit_questions where user_id = :u").param("u", u)
                .query(Long.class).single()).isZero();
    }

    @Test
    void 마이그레이션이_시드_질문을_넣지_않는다_테이블은_사용자_글만_가진다() {
        assertThat(jdbc.sql("select count(*) from visit_questions where user_id not in (select id from users)")
                .query(Long.class).single()).isZero();
    }
}
