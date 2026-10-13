package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;

import com.oraegyeot.seniorpet.push.PushMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 할 일 푸시 문구 단위 테스트: 말줄임·본문 조립, 그리고 금지어 검사.
 * 금지어 목록 출처: 클라이언트 저장소 apps/web/src/lib/historyText.ts 의 FORBIDDEN(기존 findForbidden 목록)
 * + 기획서 "내 할 일 일정" 6-2 의 권유형 추가 목록(findTodoForbidden 제안). 클라이언트와 목록이 바뀌면 여기도 맞춘다.
 */
class TodoPushTextTest {

    /** findForbidden 기존 목록 */
    private static final List<Pattern> FORBIDDEN = new ArrayList<>(List.of(
            p("이상"), p("위험"), p("진단"), p("정상"), p("경고"), p("주의"), p("악화"), p("호전"), p("좋아"), p("나빠"),
            p("늘었"), p("줄었"), p("증가"), p("감소"), p("준수"), p("순응"), p("퍼센트"), p("%")));

    /** 권유형·의료 단정·독촉 추가 목록(기획서 6-2). "추천하지 않아요"의 '추천' 자체는 막지 않고 '추천해'만 막는다. */
    private static final List<Pattern> FORBIDDEN_EXTRA = List.of(
            p("시간입니다"), p("시간이에요"), p("하세요"), p("드세요"), p("받으세요"), p("맞으세요"), p("해야"), p("꼭"),
            p("놓치"), p("잊지"), p("미루"), p("필요"), p("권장"), p("추천해"));

    private static Pattern p(String s) {
        return Pattern.compile(Pattern.quote(s));
    }

    private static String findForbidden(String text) {
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(text).find()) {
                return pattern.pattern();
            }
        }
        for (Pattern pattern : FORBIDDEN_EXTRA) {
            if (pattern.matcher(text).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    private static TodoDispatchQueries.Candidate candidate(String pet, String title, LocalTime time) {
        return new TodoDispatchQueries.Candidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), pet, title,
                LocalDate.of(2030, 6, 12), time, Instant.parse("2030-06-01T00:00:00Z"));
    }

    @Test
    void 금지어_검사기_자체가_동작한다() {
        assertThat(findForbidden("접종할 시간입니다")).isNotNull();
        assertThat(findForbidden("꼭 가세요")).isNotNull();
        assertThat(findForbidden("정상")).isNotNull();
        assertThat(findForbidden("오래곁은 일정을 정해 주거나 추천하지 않아요")).isNull();
        assertThat(findForbidden("내일 일정이 있어요")).isNull();
    }

    @Test
    void 제목_2종은_금지어와_권유형이_없다() {
        assertThat(TodoDispatcher.TITLE_DAY_BEFORE).isEqualTo("내일 일정이 있어요");
        assertThat(TodoDispatcher.TITLE_DUE_DAY).isEqualTo("오늘 일정이 있어요");
        assertThat(findForbidden(TodoDispatcher.TITLE_DAY_BEFORE)).isNull();
        assertThat(findForbidden(TodoDispatcher.TITLE_DUE_DAY)).isNull();
        assertThat(TodoDispatcher.title(TodoDispatch.DAY_BEFORE)).isEqualTo(TodoDispatcher.TITLE_DAY_BEFORE);
        assertThat(TodoDispatcher.title(TodoDispatch.DUE_DAY)).isEqualTo(TodoDispatcher.TITLE_DUE_DAY);
    }

    @Test
    void 서비스가_만드는_고정_문구_전체가_금지어를_통과한다() {
        // 메시지 조립에 쓰이는 서비스 문구: 제목 2종 + 구분자. 본문의 반려동물 이름·제목은 보호자 입력이라 검사 대상이 아니다
        for (String fixed : List.of(TodoDispatcher.TITLE_DAY_BEFORE, TodoDispatcher.TITLE_DUE_DAY,
                TodoDispatcher.SEPARATOR, TodoDispatcher.ELLIPSIS)) {
            assertThat(findForbidden(fixed)).as(fixed).isNull();
        }
        // 입력이 중립적이면 조립 결과(제목+본문)도 통과 — 서비스가 덧붙이는 문장이 없다는 확인
        for (String kind : List.of(TodoDispatch.DAY_BEFORE, TodoDispatch.DUE_DAY)) {
            for (LocalTime time : new LocalTime[] {null, LocalTime.of(14, 30)}) {
                PushMessage m = TodoDispatcher.message(candidate("초코", "접종", time), kind);
                assertThat(findForbidden(m.title() + " " + m.body())).as(kind).isNull();
            }
        }
    }

    @Test
    void 본문_조립_시각_유무() {
        assertThat(TodoDispatcher.body("초코", "접종", null)).isEqualTo("초코 · 접종");
        assertThat(TodoDispatcher.body("초코", "접종", LocalTime.of(9, 5))).isEqualTo("초코 · 접종 · 09:05");
        assertThat(TodoDispatcher.body("초코", "  접종  ", null)).isEqualTo("초코 · 접종");
    }

    @Test
    void 말줄임_경계_반려동물_10자_제목_20자() {
        assertThat(TodoDispatcher.truncate("1234567890", 10)).isEqualTo("1234567890");
        assertThat(TodoDispatcher.truncate("12345678901", 10)).isEqualTo("123456789…");
        assertThat(TodoDispatcher.body("가".repeat(10), "나".repeat(20), null))
                .isEqualTo("가".repeat(10) + " · " + "나".repeat(20));
        assertThat(TodoDispatcher.body("가".repeat(11), "나".repeat(21), LocalTime.of(14, 30)))
                .isEqualTo("가".repeat(9) + "… · " + "나".repeat(19) + "… · 14:30");
        String ten = "😀".repeat(10);
        assertThat(TodoDispatcher.truncate(ten, 10)).isEqualTo(ten);
        assertThat(TodoDispatcher.truncate(ten + "😀", 10)).isEqualTo("😀".repeat(9) + "…");
        assertThat(TodoDispatcher.truncate(null, 10)).isEmpty();
    }

    @Test
    void 메시지_data는_ID_날짜_종류_시각만_담는다() {
        TodoDispatchQueries.Candidate c = candidate("초코", "제목 비밀", LocalTime.of(7, 0));
        PushMessage m = TodoDispatcher.message(c, TodoDispatch.DUE_DAY);
        assertThat(m.data().keySet()).containsExactlyInAnyOrder("type", "todoId", "petId", "dueDate", "kind", "dueTime");
        assertThat(m.data().values()).noneMatch(v -> v.contains("제목 비밀"));
        assertThat(m.data()).containsEntry("type", "todo_reminder").containsEntry("dueTime", "07:00");
        PushMessage noTime = TodoDispatcher.message(candidate("초코", "접종", null), TodoDispatch.DAY_BEFORE);
        assertThat(noTime.data()).doesNotContainKey("dueTime");
    }

    @Test
    void collapseKey는_apns_collapse_id_한도_64바이트_이하() {
        for (String kind : List.of(TodoDispatch.DAY_BEFORE, TodoDispatch.DUE_DAY)) {
            PushMessage m = TodoDispatcher.message(candidate("초코", "접종", null), kind);
            assertThat(m.collapseKey()).startsWith("todo:");
            assertThat(m.collapseKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
        }
    }
}
