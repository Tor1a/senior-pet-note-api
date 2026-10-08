package com.oraegyeot.seniorpet.reminder;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.medlog.MedLogRepository;
import com.oraegyeot.seniorpet.push.DeviceTokenService;
import com.oraegyeot.seniorpet.push.PushMessage;
import com.oraegyeot.seniorpet.push.PushResult;
import com.oraegyeot.seniorpet.push.PushSender;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator.Slot;
import com.oraegyeot.seniorpet.reminder.ReminderDispatchQueries.Candidate;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 투약 알림 발송 작업. ReminderScheduler 가 매 분 부르고, 테스트는 dispatchDue() 를 직접 부른다.
 *
 * 흐름(회차 = 약 + 기록 날짜 + 시각):
 * 1) 창 = (지금 - catchUp, 지금]. 창 안의 정각 분들을 회차(기록 날짜, 시각, 발송 시각)로 바꾼다(RecordDateCalculator)
 * 2) 그 시각들이 투약 시각에 들어 있는 켜진 규칙 + 활성 약을 조회(ReminderDispatchQueries)
 * 3) 규칙이 그 기록 날짜에 해당하는지, 규칙·약을 바꾼 시각 이전 회차가 아닌지(소급 발송 방지) 확인
 * 4) reminder_dispatches 에 선점 insert(on conflict do nothing). 이미 있으면 건너뜀 → 회차당 최대 1회
 * 5) 이미 투약 체크 → skipped_taken / 기기 없음 → no_device / 그 외 발송 → sent·failed, 무효 토큰 삭제
 * FCM 호출은 DB 트랜잭션 밖에서 한다(선점·결과 반영은 각각 짧은 문장). 한 회차의 오류가 다른 회차를 막지 않는다.
 */
@Component
public class ReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatcher.class);

    /** 알림 제목(designer G1 확정 문구) */
    static final String TITLE = "투약 시간이에요";

    static final int PET_NAME_MAX = 10;
    static final int MED_NAME_MAX = 20;
    static final int DOSE_MAX = 10;
    static final String ELLIPSIS = "…";
    static final String SEPARATOR = " · ";

    /** 푸시 data.type */
    static final String DATA_TYPE = "med_reminder";

    private final ReminderDispatchQueries queries;
    private final MedLogRepository medLogRepository;
    private final DeviceTokenService deviceTokenService;
    private final PushSender pushSender;
    private final RecordDateCalculator recordDates;
    private final ReminderProperties props;

    public ReminderDispatcher(ReminderDispatchQueries queries, MedLogRepository medLogRepository,
                              DeviceTokenService deviceTokenService, PushSender pushSender,
                              RecordDateCalculator recordDates, ReminderProperties props) {
        this.queries = queries;
        this.medLogRepository = medLogRepository;
        this.deviceTokenService = deviceTokenService;
        this.pushSender = pushSender;
        this.recordDates = recordDates;
        this.props = props;
    }

    /** 지금 보낼 회차를 모두 처리한다. */
    public void dispatchDue() {
        Instant now = recordDates.now();
        List<Slot> slots = recordDates.minuteSlotsBetween(now.minus(props.catchUpOrDefault()), now);
        Map<LocalTime, Slot> slotByTime = new LinkedHashMap<>();
        for (Slot s : slots) {
            slotByTime.put(s.time(), s); // 창이 24시간보다 짧으므로 시각마다 회차는 1개
        }
        for (Candidate c : queries.findCandidates(slotByTime.keySet())) {
            for (LocalTime t : c.times()) {
                Slot slot = slotByTime.get(t);
                if (slot == null) {
                    continue;
                }
                try {
                    dispatchSlot(c, slot);
                } catch (RuntimeException e) {
                    log.error("투약 알림 회차 처리 실패: medicationId={}, recordDate={}, time={}",
                            c.medicationId(), slot.recordDate(), slot.time(), e);
                }
            }
        }
    }

    private void dispatchSlot(Candidate c, Slot slot) {
        if (!c.rule().matches(slot.recordDate())) {
            return;
        }
        // 소급 발송 방지: 08:05 에 08:00 알림을 켜거나 약 시각을 바꿨다면 08:00 회차는 보내지 않는다
        if (slot.fireAt().isBefore(c.reminderUpdatedAt()) || slot.fireAt().isBefore(c.medicationUpdatedAt())) {
            return;
        }
        Optional<Long> claimed = queries.claim(c.userId(), c.medicationId(), slot.recordDate(), slot.time(),
                slot.fireAt());
        if (claimed.isEmpty()) {
            return; // 이미 선점된 회차(이전 tick 또는 다른 인스턴스)
        }
        long dispatchId = claimed.get();

        if (medLogRepository.existsByUserIdAndMedicationIdAndRecordDateAndScheduledTime(
                c.userId(), c.medicationId(), slot.recordDate(), slot.time())) {
            queries.finish(dispatchId, ReminderDispatch.SKIPPED_TAKEN, 0, 0);
            return;
        }
        List<String> tokens = deviceTokenService.tokensOf(c.userId());
        if (tokens.isEmpty()) {
            queries.finish(dispatchId, ReminderDispatch.NO_DEVICE, 0, 0);
            return;
        }

        List<PushResult> results;
        try {
            results = pushSender.send(tokens, message(c, slot));
        } catch (RuntimeException e) {
            queries.finish(dispatchId, ReminderDispatch.FAILED, 0, tokens.size());
            throw e;
        }
        int success = 0;
        List<String> invalid = results.stream()
                .filter(r -> r.status() == PushResult.Status.INVALID_TOKEN).map(PushResult::token).toList();
        for (PushResult r : results) {
            if (r.status() == PushResult.Status.SUCCESS) {
                success++;
            }
        }
        // 결과를 먼저 기록하고 무효 토큰을 지운다(토큰 삭제가 실패해도 발송 기록은 남게)
        queries.finish(dispatchId, success > 0 ? ReminderDispatch.SENT : ReminderDispatch.FAILED,
                success, results.size() - success);
        deviceTokenService.removeInvalid(c.userId(), invalid);
    }

    /**
     * 앞뒤 공백을 지우고 코드 포인트 max 자를 넘으면 앞 max-1자 + "…" 로 줄인다(null 은 빈 문자열).
     */
    static String truncate(String s, int max) {
        String t = s == null ? "" : s.strip();
        if (t.codePointCount(0, t.length()) <= max) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, max - 1)) + ELLIPSIS;
    }

    /** 본문 "{반려동물} · {약} {용량}"(용량 없으면 생략). 반려동물 10자·약 20자·용량 10자 말줄임. */
    static String body(String petName, String medicationName, String doseText) {
        String dose = truncate(doseText, DOSE_MAX);
        return truncate(petName, PET_NAME_MAX) + SEPARATOR + truncate(medicationName, MED_NAME_MAX)
                + (dose.isEmpty() ? "" : " " + dose);
    }

    /** 제목 "투약 시간이에요", 본문 body(), data 는 화면 이동용 문자열 5개 */
    static PushMessage message(Candidate c, Slot slot) {
        String body = body(c.petName(), c.medicationName(), c.doseText());
        String time = TimeOfDay.format(slot.time());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", DATA_TYPE);
        data.put("medicationId", c.medicationId().toString());
        data.put("petId", c.petId().toString());
        data.put("recordDate", slot.recordDate().toString());
        data.put("scheduledTime", time);
        String collapseKey = c.medicationId() + ":" + slot.recordDate() + ":" + time;
        return new PushMessage(TITLE, body, Collections.unmodifiableMap(data), collapseKey);
    }
}
