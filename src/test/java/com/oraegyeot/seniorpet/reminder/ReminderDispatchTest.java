package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.oraegyeot.seniorpet.ApiTestSupport;
import com.oraegyeot.seniorpet.push.PushMessage;
import com.oraegyeot.seniorpet.push.PushResult;
import com.oraegyeot.seniorpet.push.PushSender;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 알림 발송 작업(ReminderDispatcher.dispatchDue) 통합 테스트.
 * MutableClock 으로 시각을 고정하고 dispatchDue() 를 직접 부른다(테스트 프로필은 스케줄러 꺼짐).
 * 발송은 FakePushSender 로 받는다. 테스트 DB 에 다른 테스트의 데이터가 있을 수 있으므로
 * 단언은 이 테스트가 만든 기기 토큰·medicationId 로 거른다.
 * 기준 날짜 2026-10-12 = 월요일. 약은 07:00 에 등록하고 알림은 08:00 회차를 본다.
 */
class ReminderDispatchTest extends ApiTestSupport {

    @Autowired
    private ReminderDispatcher dispatcher;

    @Autowired
    private PushSender injectedSender;

    /** 테스트 사용자 1명 분량: 반려동물(초코) + 약(아조딜 1캡슐) + 알림 규칙 + (선택) 기기 토큰 */
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

    private Fixture dailyAt8() throws Exception {
        return fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), true);
    }

    private Optional<Map<String, Object>> dispatchRow(String medId) {
        return jdbc.sql("""
                        select status, success_count, failure_count, record_date, scheduled_time, fire_at
                          from reminder_dispatches where medication_id = :m
                        """)
                .param("m", UUID.fromString(medId))
                .query().listOfRows().stream().findFirst();
    }

    private String statusOf(String medId) {
        return dispatchRow(medId).map(r -> (String) r.get("status")).orElse(null);
    }

    private void at(int day, int hour, int minute) {
        setSeoulTime(2026, 10, day, hour, minute);
    }

    @Test
    void 테스트_컨텍스트의_발송기는_가짜_발송기() {
        assertThat(injectedSender).isSameAs(pushSender);
    }

    @Test
    void 정시에_1건_발송하고_제목_본문_data가_계약과_같다() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        at(12, 8, 0);
        dispatcher.dispatchDue();

        List<PushMessage> sent = pushSender.sentTo(f.fcmToken());
        assertThat(sent).hasSize(1);
        PushMessage m = sent.get(0);
        assertThat(m.title()).isEqualTo("투약 시간이에요");
        assertThat(m.body()).isEqualTo("초코 · 아조딜 1캡슐");
        assertThat(m.data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "type", "med_reminder",
                "medicationId", f.medId(),
                "petId", f.petId(),
                "recordDate", "2026-10-12",
                "scheduledTime", "08:00"));
        assertThat(m.collapseKey()).isEqualTo(f.medId() + ":2026-10-12:08:00");

        Map<String, Object> row = dispatchRow(f.medId()).orElseThrow();
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(((Number) row.get("success_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("failure_count")).intValue()).isZero();
    }

    @Test
    void 용량이_없으면_본문에서_생략() throws Exception {
        at(12, 7, 0);
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String med = call(jsonPost("/api/pets/" + pet + "/medications",
                Map.of("name", "심장약", "times", List.of("08:00"))), token, 201).get("id").asText();
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "ios");
        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(fcm)).extracting(PushMessage::body).containsExactly("초코 · 심장약");
    }

    @Test
    void 같은_회차는_여러_번_호출해도_1건() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        at(12, 8, 0);
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        at(12, 8, 1);
        dispatcher.dispatchDue(); // 08:00 회차가 아직 늦게라도 보내는 창 안에 있지만 이미 선점됨
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
    }

    @Test
    void 동시에_호출해도_선점_유니크_키로_1건() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        at(12, 8, 0);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new java.util.ArrayList<>();
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
        assertThat(statusOf(f.medId())).isEqualTo("sent");
    }

    @Test
    void 이미_투약_체크한_회차는_보내지_않고_skipped_taken() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        at(12, 7, 59);
        call(jsonPost("/api/med-logs", Map.of("medicationId", f.medId(), "scheduledTime", "08:00")), f.token(), 201);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(statusOf(f.medId())).isEqualTo("skipped_taken");
    }

    @Test
    void 기기가_없으면_no_device() throws Exception {
        at(12, 7, 0);
        Fixture f = fixture(List.of("08:00"), Map.of("enabled", true, "repeat", "daily"), false);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(statusOf(f.medId())).isEqualTo("no_device");
    }

    @Test
    void 새벽_02시_회차는_기록날짜_다음날_02시에_기록날짜_요일로_발송() throws Exception {
        at(12, 5, 0); // 월요일 05:00 에 "월요일만, 02:00" 설정
        Fixture f = fixture(List.of("02:00"), Map.of("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("mon")), true);

        at(12, 2, 0); // 월요일 02:00 = 일요일 회차(설정 전이기도 함) → 없음
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();

        at(13, 2, 0); // 화요일 02:00 = 월요일 회차 → 발송
        dispatcher.dispatchDue();
        List<PushMessage> sent = pushSender.sentTo(f.fcmToken());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).data()).containsEntry("recordDate", "2026-10-12").containsEntry("scheduledTime", "02:00");
        Map<String, Object> row = dispatchRow(f.medId()).orElseThrow();
        assertThat(row.get("record_date").toString()).isEqualTo("2026-10-12");
        assertThat(toInstant(row.get("fire_at"))).isEqualTo(Instant.parse("2026-10-12T17:00:00Z"));

        at(14, 2, 0); // 수요일 02:00 = 화요일 회차 → 요일 불일치
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
    }

    @Test
    void 규칙에_해당하지_않으면_보내지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture wrongDay = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "weekly", "daysOfWeek", List.of("tue")), true);
        Fixture disabled = fixture(List.of("08:00"), Map.of("enabled", false, "repeat", "daily"), true);
        Fixture inactive = dailyAt8();
        call(delete("/api/medications/" + inactive.medId()), inactive.token(), 204);
        Fixture notStarted = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "daily", "startDate", "2026-10-13"), true);
        Fixture ended = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "daily", "startDate", "2026-10-01", "endDate", "2026-10-11"), true);
        Fixture intervalOff = fixture(List.of("08:00"),
                Map.of("enabled", true, "repeat", "interval", "intervalDays", 3, "startDate", "2026-10-10"), true);

        at(12, 8, 0);
        dispatcher.dispatchDue();
        for (Fixture f : List.of(wrongDay, disabled, inactive, notStarted, ended, intervalOff)) {
            assertThat(pushSender.sentTo(f.fcmToken())).as(f.medId()).isEmpty();
            assertThat(dispatchRow(f.medId())).as(f.medId()).isEmpty();
        }
    }

    @Test
    void 지연_9분이면_늦게라도_보낸다() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        at(12, 8, 9); // 서버 재시작 등으로 08:00~08:08 tick 을 놓친 경우
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
        assertThat(pushSender.sentTo(f.fcmToken()).get(0).data()).containsEntry("scheduledTime", "08:00");
    }

    @Test
    void 창_끝_경계_08시10분과_08시11분에는_08시_회차를_버린다() throws Exception {
        at(13, 7, 0);
        Fixture f = dailyAt8();
        at(13, 8, 10); // 창 = (08:00, 08:10] → 08:00 제외
        dispatcher.dispatchDue();
        at(13, 8, 11);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcmToken())).isEmpty();
        assertThat(dispatchRow(f.medId())).isEmpty();
    }

    @Test
    void 알림을_켠_시각_이전_회차는_소급_발송하지_않는다() throws Exception {
        at(12, 7, 0);
        String token = newDisposableUserToken();
        String med = createMedication(token, createPet(token), List.of("08:00"));
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "web");

        at(12, 8, 5); // 08:05 에 알림을 켬
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
        dispatcher.dispatchDue();
        at(12, 8, 6);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(fcm)).isEmpty();
        assertThat(dispatchRow(med)).isEmpty();

        at(13, 8, 0); // 다음 날 회차는 보낸다
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(fcm)).hasSize(1);
    }

    @Test
    void 무효_토큰은_삭제하고_다른_토큰은_유지한다() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        String invalid = "fcm-invalid-" + UUID.randomUUID();
        registerDevice(f.token(), invalid, "ios");
        pushSender.willReturn(invalid, PushResult.Status.INVALID_TOKEN);

        at(12, 8, 0);
        dispatcher.dispatchDue();

        assertThat(jdbc.sql("select count(*) from device_tokens where token = :t").param("t", invalid)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from device_tokens where token = :t").param("t", f.fcmToken())
                .query(Long.class).single()).isEqualTo(1L);
        Map<String, Object> row = dispatchRow(f.medId()).orElseThrow();
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(((Number) row.get("success_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("failure_count")).intValue()).isEqualTo(1);
    }

    @Test
    void 모든_토큰이_실패하면_failed이고_토큰은_유지() throws Exception {
        at(12, 7, 0);
        Fixture f = dailyAt8();
        pushSender.willReturn(f.fcmToken(), PushResult.Status.FAILED);
        at(12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(statusOf(f.medId())).isEqualTo("failed");
        assertThat(jdbc.sql("select count(*) from device_tokens where token = :t").param("t", f.fcmToken())
                .query(Long.class).single()).isEqualTo(1L);
        at(12, 8, 1);
        dispatcher.dispatchDue(); // 재발송하지 않는다(최대 1회)
        assertThat(pushSender.sentTo(f.fcmToken())).hasSize(1);
    }

    @Test
    void 한_회차의_예외가_다른_회차를_막지_않는다() throws Exception {
        at(12, 7, 0);
        Fixture broken = dailyAt8();
        Fixture healthy = dailyAt8();
        pushSender.willThrowFor(broken.fcmToken());

        at(12, 8, 0);
        dispatcher.dispatchDue();

        assertThat(pushSender.sentTo(healthy.fcmToken())).hasSize(1);
        assertThat(statusOf(healthy.medId())).isEqualTo("sent");
        assertThat(statusOf(broken.medId())).isEqualTo("failed");
    }

    private static Instant toInstant(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return OffsetDateTime.parse(value.toString().replace(' ', 'T')).withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }
}
