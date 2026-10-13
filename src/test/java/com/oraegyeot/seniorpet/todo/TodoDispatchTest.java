package com.oraegyeot.seniorpet.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.push.PushMessage;
import com.oraegyeot.seniorpet.push.PushResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 할 일 알림 발송 작업(TodoDispatcher.dispatchDue) 통합 테스트.
 * MutableClock 으로 시각을 고정하고 dispatchDue() 를 직접 부른다(테스트 프로필은 스케줄러 꺼짐).
 * 테스트 DB 에 다른 테스트의 데이터가 있을 수 있으므로 단언은 이 테스트가 만든 기기 토큰·todoId 로 거른다.
 *
 * 날짜는 2030-06 로 둔다: 수정(PUT)·완료는 DB 트리거가 updated_at 을 실제 now() 로 덮어쓰므로,
 * 가짜 시계가 실제 현재보다 앞서 있어야 "소급 방지"(발송 시각 < updated_at)에 걸리지 않는다.
 * 기준: 예정 날짜 2030-06-12 → 하루 전 알림 06-11 20:00, 당일 알림 06-12 09:00 (서울).
 */
class TodoDispatchTest extends ApiTestSupport {

    private static final int Y = 2030;

    @Autowired
    private TodoDispatcher dispatcher;

    private record Fixture(String token, String petId, String fcmToken) {
    }

    private void at(int month, int day, int hour, int minute) {
        setSeoulTime(Y, month, day, hour, minute);
    }

    private Fixture fixture(boolean withDevice) throws Exception {
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String fcm = null;
        if (withDevice) {
            fcm = "fcm-" + UUID.randomUUID();
            registerDevice(token, fcm, "android");
        }
        return new Fixture(token, pet, fcm);
    }

    /** 06-10 12:00 에 등록(발송 시각보다 앞) */
    private String todo(Fixture f, String title, String dueDate, String dueTime) throws Exception {
        at(6, 10, 12, 0);
        return createTodo(f.token(), f.petId(), title, dueDate, dueTime).get("id").asText();
    }

    private List<Map<String, Object>> rows(String todoId) {
        return jdbc.sql("""
                        select kind, due_date::text as due_date, status, success_count, failure_count
                          from todo_dispatches where todo_id = :t order by kind, due_date
                        """)
                .param("t", UUID.fromString(todoId)).query().listOfRows();
    }

    private Optional<String> statusOf(String todoId, String kind) {
        return rows(todoId).stream().filter(r -> kind.equals(r.get("kind"))).map(r -> (String) r.get("status")).findFirst();
    }

    @Test
    void 하루_전_20시에_1건_발송하고_제목_본문_data가_계약과_같다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "종합 접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();

        List<PushMessage> sent = pushSender.sentTo(f.fcmToken());
        assertThat(sent).hasSize(1);
        PushMessage m = sent.get(0);
        assertThat(m.title()).isEqualTo("내일 일정이 있어요");
        assertThat(m.body()).isEqualTo("초코 · 종합 접종");
        assertThat(m.data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "type", "todo_reminder", "todoId", id, "petId", f.petId(), "dueDate", "2030-06-12",
                "kind", "day_before"));
        assertThat(m.collapseKey()).isEqualTo("todo:" + id + ":day_before:2030-06-12");

        List<Map<String, Object>> rows = rows(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("sent");
        assertThat(((Number) rows.get(0).get("success_count")).intValue()).isEqualTo(1);
        assertThat(((Number) rows.get(0).get("failure_count")).intValue()).isZero();
    }

    @Test
    void 시각이_있으면_본문_끝에_붙고_data에_dueTime이_들어간다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "검진", "2030-06-12", "14:30");
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        PushMessage m = pushSender.sentTo(f.fcmToken()).get(0);
        assertThat(m.body()).isEqualTo("초코 · 검진 · 14:30");
        assertThat(m.data()).containsEntry("dueTime", "14:30").containsEntry("todoId", id);
    }

    @Test
    void 당일_09시에_1건_발송() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "구충", "2030-06-12", "10:00");
        at(6, 12, 9, 0);
        dispatcher.dispatchDue();

        List<PushMessage> sent = pushSender.sentTo(f.fcmToken());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).title()).isEqualTo("오늘 일정이 있어요");
        assertThat(sent.get(0).data()).containsEntry("kind", "due_day").containsEntry("dueDate", "2030-06-12");
        assertThat(sent.get(0).collapseKey()).isEqualTo("todo:" + id + ":due_day:2030-06-12");
        assertThat(statusOf(id, "due_day")).contains("sent");
        assertThat(statusOf(id, "day_before")).isEmpty(); // 하루 전 시각은 지났고 창 밖
    }

    @Test
    void 하루_전과_당일_둘_다_각각_1건() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        at(6, 12, 9, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).extracting(m -> m.data().get("kind"))
                .containsExactly("day_before", "due_day");
        assertThat(rows(id)).hasSize(2);
    }

    @Test
    void 알림_시각이_아닌_때는_보내지_않는다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        for (int[] t : new int[][] {{6, 11, 19, 59}, {6, 11, 21, 0}, {6, 12, 8, 59}, {6, 12, 12, 0}, {6, 13, 9, 0}}) {
            at(t[0], t[1], t[2], t[3]);
            dispatcher.dispatchDue();
        }
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(id)).isEmpty();
    }

    @Test
    void 같은_시각에_여러_번_호출해도_1건() throws Exception {
        Fixture f = fixture(true);
        todo(f, "접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        at(6, 11, 20, 1);
        dispatcher.dispatchDue(); // 아직 늦게라도 보내는 창 안이지만 이미 선점됨
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
    }

    @Test
    void 동시에_호출해도_선점_유니크_키로_1건() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    dispatcher.dispatchDue();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> fu : futures) {
                fu.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
        assertThat(statusOf(id, "day_before")).contains("sent");
    }

    @Test
    void 완료한_일정은_발송하지_않는다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 12, 0);
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), f.token(), 200);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        at(6, 12, 9, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(id)).isEmpty();
    }

    @Test
    void 완료를_되돌리면_이후_알림은_다시_간다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 12, 0);
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", true)), f.token(), 200);
        call(jsonPut("/api/todos/" + id + "/completion", Map.of("completed", false)), f.token(), 200);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
    }

    @Test
    void 기기가_없으면_no_device로_기록만() throws Exception {
        Fixture f = fixture(false);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(statusOf(id, "day_before")).contains("no_device");
    }

    @Test
    void remind_false_삭제된_일정_과거_날짜는_발송하지_않는다() throws Exception {
        Fixture f = fixture(true);
        at(6, 10, 12, 0);
        Map<String, Object> off = new HashMap<>(Map.of("title", "끔", "dueDate", "2030-06-12", "remind", false));
        String offId = call(jsonPost("/api/pets/" + f.petId() + "/todos", off), f.token(), 201).get("id").asText();
        String deleted = todo(f, "삭제됨", "2030-06-12", null);
        call(delete("/api/todos/" + deleted), f.token(), 204);
        String past = todo(f, "지난 일정", "2030-06-01", "09:00");
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        at(6, 12, 9, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(offId)).isEmpty();
        assertThat(rows(deleted)).isEmpty();
        assertThat(rows(past)).isEmpty();
    }

    @Test
    void 지연_9분까지_보내고_10분_이후는_버린다() throws Exception {
        Fixture f = fixture(true);
        Fixture g = fixture(true);
        todo(f, "접종", "2030-06-12", null);
        todo(g, "접종", "2030-06-12", null);
        at(6, 11, 20, 9);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
        assertThat(pushSender.sentTo(g.fcmToken())).hasSize(1);

        Fixture late = fixture(true);
        String lateId = todo(late, "접종", "2030-06-12", null);
        at(6, 11, 20, 10);
        dispatcher.dispatchDue(); // (now - 10분, now] 라 20:00 은 제외
        at(6, 11, 20, 11);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(late.fcmToken())).isEmpty();
        assertThat(rows(lateId)).isEmpty();
    }

    @Test
    void 발송_시각_뒤에_등록하면_그_알림은_보내지_않고_당일_알림은_정상() throws Exception {
        Fixture f = fixture(true);
        at(6, 11, 20, 5);
        String id = createTodo(f.token(), f.petId(), "접종", "2030-06-12", null).get("id").asText();
        dispatcher.dispatchDue(); // 20:00 알림은 등록보다 앞 → 소급 발송 안 함
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(id)).isEmpty();
        at(6, 12, 9, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).extracting(m -> m.data().get("kind")).containsExactly("due_day");
    }

    @Test
    void 당일_09시_이후에_등록하면_당일_알림은_없다() throws Exception {
        Fixture f = fixture(true);
        at(6, 12, 9, 5);
        createTodo(f.token(), f.petId(), "접종", "2030-06-12", null);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
    }

    @Test
    void 날짜를_고치면_새_날짜로_다시_알리고_같은_날짜로_재저장해도_중복은_없다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);

        // 같은 날짜로 재저장 → 같은 (할 일, 종류, 날짜) 키라 중복 발송 없음
        at(6, 11, 20, 1);
        call(jsonPut("/api/todos/" + id, Map.of("title", "접종", "dueDate", "2030-06-12", "memo", "수정")), f.token(), 200);
        at(6, 11, 20, 2);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);

        // 날짜를 6/13 으로 고침 → 6/12 20:00 에 새 날짜 기준 하루 전 알림
        at(6, 11, 20, 3);
        call(jsonPut("/api/todos/" + id, Map.of("title", "접종", "dueDate", "2030-06-13")), f.token(), 200);
        at(6, 12, 20, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(2);
        assertThat(pushSender.sentTo(f.fcmToken()).get(1).data()).containsEntry("dueDate", "2030-06-13");
        assertThat(rows(id)).extracting(r -> r.get("due_date")).containsExactly("2030-06-12", "2030-06-13");
    }

    @Test
    void 푸시에는_메모와_병원_메모가_실리지_않는다() throws Exception {
        Fixture f = fixture(true);
        at(6, 10, 12, 0);
        Map<String, Object> b = new HashMap<>(Map.of("title", "접종", "dueDate", "2030-06-12",
                "memo", "MEMO-SECRET", "hospitalNote", "NOTE-SECRET"));
        call(jsonPost("/api/pets/" + f.petId() + "/todos", b), f.token(), 201);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        PushMessage m = pushSender.sentTo(f.fcmToken()).get(0);
        String all = m.title() + m.body() + m.data() + m.collapseKey();
        assertThat(all).doesNotContain("SECRET");
        assertThat(m.data()).doesNotContainKeys("title", "memo", "hospitalNote");
        assertThat(m.data().values()).doesNotContain("접종");
    }

    @Test
    void 말줄임_경계_반려동물_10자_제목_20자() throws Exception {
        String token = newDisposableUserToken();
        String pet = call(jsonPost("/api/pets", Map.of("name", "가".repeat(11), "species", "cat")), token, 201)
                .get("id").asText();
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "ios");
        at(6, 10, 12, 0);
        createTodo(token, pet, "나".repeat(21), "2030-06-12", null);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(fcm).get(0).body())
                .isEqualTo("가".repeat(9) + "… · " + "나".repeat(19) + "…");
    }

    @Test
    void 무효_토큰은_삭제되고_기록은_failed() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        pushSender.willReturn(f.fcmToken(), PushResult.Status.INVALID_TOKEN);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(statusOf(id, "day_before")).contains("failed");
        assertThat(jdbc.sql("select count(*) from device_tokens where token = :t").param("t", f.fcmToken())
                .query(Long.class).single()).isZero();
    }

    @Test
    void 일시_실패_토큰은_유지하고_재시도하지_않는다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        pushSender.willReturn(f.fcmToken(), PushResult.Status.FAILED);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        at(6, 11, 20, 1);
        dispatcher.dispatchDue();
        assertThat(statusOf(id, "day_before")).contains("failed");
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from device_tokens where token = :t").param("t", f.fcmToken())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 한_건의_예외가_다음_건을_막지_않는다() throws Exception {
        Fixture bad = fixture(true);
        Fixture good = fixture(true);
        String badId = todo(bad, "접종", "2030-06-12", null);
        todo(good, "접종", "2030-06-12", null);
        pushSender.willThrowFor(bad.fcmToken());
        at(6, 11, 20, 0);
        dispatcher.dispatchDue(); // 예외가 밖으로 나오지 않는다
        assertThat(pushSender.sentTo(good.fcmToken())).hasSize(1);
        assertThat(statusOf(badId, "day_before")).contains("failed");
    }

    @Test
    void 탈퇴한_사용자의_할_일은_후보에서_사라져_아무것도_보내지_않는다() throws Exception {
        Fixture f = fixture(true);
        String id = todo(f, "접종", "2030-06-12", null);
        call(jsonPost("/api/me/withdraw", Map.of("password", PASSWORD, "confirm", true)), f.token(), 204);
        at(6, 11, 20, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(id)).isEmpty();
    }
}
