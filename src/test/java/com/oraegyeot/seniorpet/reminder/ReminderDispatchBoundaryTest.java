package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.push.PushMessage;
import com.oraegyeot.seniorpet.push.PushResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 알림 발송 경계 시나리오(QA 보강). ReminderDispatchTest 에 없는 경계를 다룬다.
 * - 시작일·종료일 당일, interval 기준일, 04시·자정을 넘는 창, 초 단위 창 경계, 여러 회차 따라잡기
 * - 일부 회차만 체크, 새벽 회차 체크, 동시 발송(여러 약), 전부 무효 토큰, 기기 해제(로그아웃)·토큰 이전 후 발송
 * 주의: 규칙·약을 "수정(UPDATE)"하면 DB 트리거가 updated_at 을 DB 의 실제 now() 로 덮어쓴다.
 * 소급 방지 판정이 그 값을 쓰므로, 이 클래스는 규칙·약을 만든 뒤 수정하지 않는다(insert 는 Clock 값이 들어감).
 * 2026-10-12 = 월요일.
 */
class ReminderDispatchBoundaryTest extends ApiTestSupport {

    @Autowired
    private ReminderDispatcher dispatcher;

    private record Fixture(String token, String petId, String medId, String fcmToken) {
    }

    private Fixture fixture(List<String> times, Map<String, Object> reminder, boolean withDevice) throws Exception {
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String med = createMedication(token, pet, times);
        putReminder(token, med, reminder);
        String fcm = null;
        if (withDevice) {
            fcm = "fcm-" + UUID.randomUUID();
            registerDevice(token, fcm, "android");
        }
        return new Fixture(token, pet, med, fcm);
    }

    private void at(int day, int hour, int minute) {
        setSeoulTime(2026, 10, day, hour, minute);
    }

    private void at(int day, int hour, int minute, int second) {
        clock.set(LocalDateTime.of(2026, 10, day, hour, minute, second).atZone(SEOUL).toInstant());
    }

    private List<Map<String, Object>> rows(String medId) {
        return jdbc.sql("""
                        select status, success_count, failure_count, cast(record_date as text) as record_date,
                               to_char(scheduled_time, 'HH24:MI') as scheduled_time
                          from reminder_dispatches where medication_id = :m order by record_date, scheduled_time
                        """)
                .param("m", UUID.fromString(medId))
                .query().listOfRows();
    }

    private List<String> sentSlots(String fcm) {
        return pushSender.sentTo(fcm).stream()
                .map(m -> m.data().get("recordDate") + " " + m.data().get("scheduledTime"))
                .toList();
    }

    private long tokenCount(String fcm) {
        return jdbc.sql("select count(*) from device_tokens where token = :t").param("t", fcm)
                .query(Long.class).single();
    }

    // ---------- 반복 규칙 경계 ----------

    @Test
    void 종료일_당일은_발송하고_다음날은_발송하지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "daily", "startDate", "2026-10-12", "endDate", "2026-10-13"), true);
        at(13, 8, 0);
        dispatcher.dispatchDue();
        at(14, 8, 0);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-13 08:00");
        assertThat(rows(f.medId())).hasSize(1);
    }

    @Test
    void 종료일_당일의_새벽_회차는_종료일_다음날_새벽에_발송한다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("02:00"),
                Map.of("enabled", true, "repeat", "daily", "startDate", "2026-10-12", "endDate", "2026-10-12"), true);
        at(13, 2, 0); // 기록 날짜 10-12(종료일)의 02:00 회차
        dispatcher.dispatchDue();
        at(14, 2, 0); // 기록 날짜 10-13 → 종료 후
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 02:00");
    }

    @Test
    void 시작일_전날은_발송하지_않고_시작일_당일부터_발송한다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "daily", "startDate", "2026-10-13"), true);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        at(13, 8, 0);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-13 08:00");
    }

    @Test
    void interval은_기준일과_N일_뒤에만_발송한다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "interval", "intervalDays", 3, "startDate", "2026-10-12"), true);
        for (int day = 12; day <= 18; day++) {
            at(day, 8, 0);
            dispatcher.dispatchDue();
        }
        assertThat(sentSlots(f.fcmToken()))
                .containsExactly("2026-10-12 08:00", "2026-10-15 08:00", "2026-10-18 08:00");
    }

    @Test
    void 과거_기준일의_interval은_기준일부터_센_간격으로_발송한다() throws Exception {
        // 기준일 10-01, 4일 간격 → 10-01, 05, 09, 13, 17 … 10-12 는 해당 없음, 10-13 해당
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "interval", "intervalDays", 4, "startDate", "2026-10-01"), true);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        at(13, 8, 0);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-13 08:00");
    }

    @Test
    void 창이_04시_경계를_넘으면_03시58분과_04시_회차의_기록날짜가_다르다() throws Exception {
        at(11, 12, 0); // 일요일 낮에 "월요일만, 03:58·04:00" 설정
        Fixture f = fixture(List.of("03:58", "04:00"),
                Map.of("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon")), true);

        at(12, 4, 5); // 월 04:05: 창 (03:55, 04:05] — 03:58 = 일요일 회차(해당 없음), 04:00 = 월요일 회차
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 04:00");

        at(13, 4, 5); // 화 04:05: 03:58 = 월요일 회차(발송), 04:00 = 화요일 회차(해당 없음)
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 04:00", "2026-10-12 03:58");
        assertThat(rows(f.medId())).extracting(r -> r.get("record_date")).containsOnly("2026-10-12");
    }

    @Test
    void 창이_자정을_넘어도_전날_23시58분_회차를_늦게라도_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("23:58"), Map.of("enabled", true, "repeat", "daily"), true);
        at(13, 0, 5); // 창 (10-12 23:55, 10-13 00:05]
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 23:58");
    }

    // ---------- 10분 창 경계 · 지연 후 따라잡기 ----------

    @Test
    void 정시_1초_전에는_보내지_않고_정시_30초에는_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 7, 59, 59);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(f.medId())).isEmpty();
        at(12, 8, 0, 30);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 08:00");
    }

    @Test
    void 지연_9분59초는_보내고_10분00초는_버린다() throws Exception {
        at(12, 7, 0);
        Fixture late = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 8, 9, 59);
        dispatcher.dispatchDue();
        assertThat(sentSlots(late.fcmToken())).containsExactly("2026-10-12 08:00");

        at(13, 8, 10, 0); // 다음 날 같은 회차를 정확히 10분 늦게 → 창 (08:00:00, 08:10:00] 밖
        dispatcher.dispatchDue();
        assertThat(sentSlots(late.fcmToken())).containsExactly("2026-10-12 08:00");
    }

    @Test
    void 서버가_멈췄다_살아나면_창_안의_여러_회차를_한_번에_따라잡는다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00", "08:05"), Map.of("enabled", true, "repeat", "daily"), true);
        Fixture other = fixture(List.of("08:03"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 8, 9); // 08:00~08:08 tick 을 모두 놓침
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactlyInAnyOrder("2026-10-12 08:00", "2026-10-12 08:05");
        assertThat(sentSlots(other.fcmToken())).containsExactly("2026-10-12 08:03");

        at(12, 8, 10); // 다음 tick: 08:00 은 창 밖, 08:03·08:05 는 이미 선점 → 추가 발송 없음
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(2);
        assertThat(pushSender.sentTo(other.fcmToken())).hasSize(1);
    }

    @Test
    void 창_밖으로_놓친_회차는_버리고_창_안의_회차만_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00", "08:15"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 8, 20); // 08:00 은 20분 지연(버림), 08:15 는 5분 지연(발송)
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 08:15");
        assertThat(rows(f.medId())).extracting(r -> r.get("scheduled_time")).containsExactly("08:15");
    }

    // ---------- 투약 체크된 회차 ----------

    @Test
    void 체크한_회차만_생략하고_같은_약의_다른_회차는_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00", "20:00"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 7, 30);
        call(jsonPost("/api/med-logs", Map.of("medicationId", f.medId(), "scheduledTime", "08:00")), f.token(), 201);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        at(12, 20, 0);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-12 20:00");
        assertThat(rows(f.medId())).extracting(r -> r.get("scheduled_time") + "=" + r.get("status"))
                .containsExactly("08:00=skipped_taken", "20:00=sent");
    }

    @Test
    void 새벽_회차를_자정_이후에_체크하면_같은_기록날짜로_보고_생략한다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("02:00"), Map.of("enabled", true, "repeat", "daily"), true);
        at(13, 1, 30); // 화 01:30 = 기록 날짜 월요일
        call(jsonPost("/api/med-logs", Map.of("medicationId", f.medId(), "scheduledTime", "02:00")), f.token(), 201);
        at(13, 2, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(rows(f.medId())).extracting(r -> r.get("record_date") + "=" + r.get("status"))
                .containsExactly("2026-10-12=skipped_taken");
    }

    @Test
    void 전날_같은_시각을_체크한_것은_오늘_회차_생략_근거가_아니다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        at(12, 7, 30);
        call(jsonPost("/api/med-logs", Map.of("medicationId", f.medId(), "scheduledTime", "08:00")), f.token(), 201);
        at(13, 8, 0);
        dispatcher.dispatchDue();
        assertThat(sentSlots(f.fcmToken())).containsExactly("2026-10-13 08:00");
    }

    // ---------- 동시 발송 ----------

    @Test
    void 여러_약을_여러_스레드가_동시에_처리해도_회차마다_1건() throws Exception {
        at(12, 7, 0);
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fixtures.add(fixture(List.of("08:00", "08:02"), Map.of("enabled", true, "repeat", "daily"), true));
        }
        at(12, 8, 5); // 08:00·08:02 두 회차가 모두 창 안
        int threads = 8;
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
        for (Fixture f : fixtures) {
            assertThat(sentSlots(f.fcmToken())).as(f.medId())
                    .containsExactlyInAnyOrder("2026-10-12 08:00", "2026-10-12 08:02");
            assertThat(rows(f.medId())).as(f.medId()).hasSize(2)
                    .allSatisfy(r -> assertThat(r.get("status")).isEqualTo("sent"));
        }
    }

    // ---------- 무효 토큰 ----------

    @Test
    void 모든_토큰이_무효면_failed이고_모두_삭제되며_다음_회차는_no_device() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        String second = "fcm-" + UUID.randomUUID();
        registerDevice(f.token(), second, "web");
        pushSender.willReturn(f.fcmToken(), PushResult.Status.INVALID_TOKEN);
        pushSender.willReturn(second, PushResult.Status.INVALID_TOKEN);

        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(tokenCount(f.fcmToken())).isZero();
        assertThat(tokenCount(second)).isZero();
        Map<String, Object> row = rows(f.medId()).get(0);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(((Number) row.get("success_count")).intValue()).isZero();
        assertThat(((Number) row.get("failure_count")).intValue()).isEqualTo(2);

        at(13, 8, 0);
        dispatcher.dispatchDue();
        assertThat(rows(f.medId())).extracting(r -> r.get("record_date") + "=" + r.get("status"))
                .containsExactly("2026-10-12=failed", "2026-10-13=no_device");
    }

    @Test
    void 무효와_일시실패가_섞이면_무효만_삭제하고_일시실패_토큰은_유지() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        String flaky = "fcm-" + UUID.randomUUID();
        registerDevice(f.token(), flaky, "ios");
        pushSender.willReturn(f.fcmToken(), PushResult.Status.INVALID_TOKEN);
        pushSender.willReturn(flaky, PushResult.Status.FAILED);

        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(tokenCount(f.fcmToken())).isZero();
        assertThat(tokenCount(flaky)).isEqualTo(1);
        assertThat(rows(f.medId()).get(0).get("status")).isEqualTo("failed");
    }

    // ---------- 기기 해제(로그아웃) · 토큰 이전 ----------

    @Test
    void 로그아웃_전에_기기를_해제하면_그_기기로_보내지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), false);
        String fcm = "fcm-" + UUID.randomUUID();
        String deviceId = registerDevice(f.token(), fcm, "android");
        call(delete("/api/devices/" + deviceId), f.token(), 204);

        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(fcm)).isEmpty();
        assertThat(rows(f.medId()).get(0).get("status")).isEqualTo("no_device");
    }

    @Test
    void 같은_기기에서_다른_사용자가_로그인해_토큰을_등록하면_이전_사용자의_알림은_그_기기로_가지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture alice = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        // bob 은 같은 기기(같은 토큰)로 로그인, 자기 약은 08:00 알림
        Fixture bob = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), false);
        registerDevice(bob.token(), alice.fcmToken(), "android");

        at(12, 8, 0);
        dispatcher.dispatchDue();
        List<PushMessage> received = pushSender.sentTo(alice.fcmToken());
        assertThat(received).extracting(m -> m.data().get("medicationId")).containsExactly(bob.medId());
        assertThat(rows(alice.medId()).get(0).get("status")).isEqualTo("no_device");
        assertThat(rows(bob.medId()).get(0).get("status")).isEqualTo("sent");
    }

    @Test
    void 다른_사용자의_기기로는_내_알림이_가지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture alice = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        String bob = newDisposableUserToken();
        String bobFcm = "fcm-" + UUID.randomUUID();
        registerDevice(bob, bobFcm, "ios");

        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(alice.fcmToken())).hasSize(1);
        assertThat(pushSender.sentTo(bobFcm)).isEmpty();
    }

    @Test
    void 사용자의_기기가_여럿이면_멀티캐스트_1건으로_모두에게_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
        String web = "fcm-" + UUID.randomUUID();
        registerDevice(f.token(), web, "web");
        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
        assertThat(pushSender.sentTo(web)).hasSize(1);
        assertThat(pushSender.sentTo(web).get(0)).isSameAs(pushSender.sentTo(f.fcmToken()).get(0));
        Map<String, Object> row = rows(f.medId()).get(0);
        assertThat(((Number) row.get("success_count")).intValue()).isEqualTo(2);
    }
}
