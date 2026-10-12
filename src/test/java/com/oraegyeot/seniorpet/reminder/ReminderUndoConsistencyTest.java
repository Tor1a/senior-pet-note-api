package com.oraegyeot.seniorpet.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.oraegyeot.seniorpet.ApiTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 저장 되돌리기(QA): 투약 체크 취소·재체크와 알림 발송 판정(skipped_taken)이 모순되지 않는다. 발송은 가짜 발송기. */
class ReminderUndoConsistencyTest extends ApiTestSupport {

    @Autowired
    private ReminderDispatcher dispatcher;

    private String statusOf(String medId) {
        return jdbc.sql("select status from reminder_dispatches where medication_id = :m")
                .param("m", UUID.fromString(medId)).query(String.class).optional().orElse(null);
    }

    private record Fx(String token, String med, String fcm) { }

    private Fx fixture() throws Exception {
        setSeoulTime(2026, 10, 12, 7, 0);
        String token = newDisposableUserToken();
        String pet = createPet(token);
        String med = createMedication(token, pet, List.of("08:00"));
        putReminder(token, med, Map.of("enabled", true, "repeat", "daily"));
        String fcm = "fcm-" + UUID.randomUUID();
        registerDevice(token, fcm, "android");
        return new Fx(token, med, fcm);
    }

    @Test
    void 체크했다가_발송_전에_취소하면_그_회차는_정상_발송된다() throws Exception {
        Fx f = fixture();
        setSeoulTime(2026, 10, 12, 7, 58);
        String id = call(jsonPost("/api/med-logs", Map.of("medicationId", f.med(), "scheduledTime", "08:00")), f.token(), 201)
                .get("id").asText();
        call(delete("/api/med-logs/" + id), f.token(), 204);
        setSeoulTime(2026, 10, 12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcm())).hasSize(1);
        assertThat(statusOf(f.med())).isEqualTo("sent");
    }

    @Test
    void 체크한_채_발송되면_skipped_taken_이고_이후_취소해도_다시_발송하지_않는다() throws Exception {
        Fx f = fixture();
        setSeoulTime(2026, 10, 12, 7, 58);
        String id = call(jsonPost("/api/med-logs", Map.of("medicationId", f.med(), "scheduledTime", "08:00")), f.token(), 201)
                .get("id").asText();
        setSeoulTime(2026, 10, 12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(statusOf(f.med())).isEqualTo("skipped_taken");
        call(delete("/api/med-logs/" + id), f.token(), 204);
        setSeoulTime(2026, 10, 12, 8, 3);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcm())).isEmpty(); // 회차당 1회: 마감된 회차는 되돌려도 다시 오지 않는다(기획 R4)
        assertThat(statusOf(f.med())).isEqualTo("skipped_taken");
    }

    @Test
    void 취소_후_재체크하면_새_id_이고_발송은_skipped_taken() throws Exception {
        Fx f = fixture();
        setSeoulTime(2026, 10, 12, 7, 50);
        String id1 = call(jsonPost("/api/med-logs", Map.of("medicationId", f.med(), "scheduledTime", "08:00")), f.token(), 201)
                .get("id").asText();
        call(delete("/api/med-logs/" + id1), f.token(), 204);
        setSeoulTime(2026, 10, 12, 7, 55);
        String id2 = call(jsonPost("/api/med-logs", Map.of("medicationId", f.med(), "scheduledTime", "08:00")), f.token(), 201)
                .get("id").asText();
        assertThat(id2).isNotEqualTo(id1);
        setSeoulTime(2026, 10, 12, 8, 0);
        dispatcher.dispatchDue();
        assertThat(pushSender.sentTo(f.fcm())).isEmpty();
        assertThat(statusOf(f.med())).isEqualTo("skipped_taken");
    }
}
