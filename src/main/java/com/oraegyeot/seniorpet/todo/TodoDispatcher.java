package com.oraegyeot.seniorpet.todo;

import com.oraegyeot.seniorpet.common.TimeOfDay;
import com.oraegyeot.seniorpet.push.DeviceTokenService;
import com.oraegyeot.seniorpet.push.PushMessage;
import com.oraegyeot.seniorpet.push.PushResult;
import com.oraegyeot.seniorpet.push.PushSender;
import com.oraegyeot.seniorpet.recorddate.RecordDateCalculator;
import com.oraegyeot.seniorpet.reminder.ReminderProperties;
import com.oraegyeot.seniorpet.todo.TodoDispatchQueries.Candidate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 할 일 알림 발송 작업(하루 전·당일). TodoScheduler 가 매 분 부르고, 테스트는 dispatchDue() 를 직접 부른다.
 * 구조는 투약 알림 ReminderDispatcher 와 같다(투약 알림 코드는 건드리지 않는다).
 *
 * 흐름(회차 = 할 일 + 종류 + 예정 날짜):
 * 1) 창 = (지금 - catchUp, 지금]. 예정 날짜가 창 근처인 알림 켬·미완료 할 일을 조회(TodoDispatchQueries)
 * 2) 종류별 발송 시각 = RecordDateCalculator.slotInstant(하루 전: 예정 날짜-1일 + dayBeforeTime / 당일: 예정 날짜 + dueDayTime)
 *    이 창 안인지 판정. 할 일을 등록·수정한 시각(updated_at) 이전 회차는 보내지 않는다(소급 발송 방지)
 * 3) todo_dispatches 에 선점 insert(on conflict do nothing). 이미 있으면 건너뜀 → 회차당 최대 1회
 * 4) 선점 뒤 완료·삭제·알림 끔·날짜 변경됐으면 skipped_done / 기기 없음 → no_device / 그 외 발송 → sent·failed, 무효 토큰 삭제
 * FCM 호출은 DB 트랜잭션 밖에서 한다. 한 회차의 오류가 다른 회차를 막지 않는다.
 * 푸시에는 반려동물 이름·제목·시각만 싣는다. 메모·병원 메모는 절대 싣지 않는다(data 에도 제목 없음).
 * 이 문구는 사용자에게 보이는 서비스 문장이다 — 권유형·의료 단정 표현 금지(TodoPushTextTest 가 검사).
 */
@Component
public class TodoDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TodoDispatcher.class);

    /** 알림 제목(designer G2 확정 전 기획서 문구) */
    static final String TITLE_DAY_BEFORE = "내일 일정이 있어요";
    static final String TITLE_DUE_DAY = "오늘 일정이 있어요";

    static final int PET_NAME_MAX = 10;
    static final int TITLE_MAX = 20;
    static final String ELLIPSIS = "…";
    static final String SEPARATOR = " · ";

    /** 푸시 data.type */
    static final String DATA_TYPE = "todo_reminder";

    private final TodoDispatchQueries queries;
    private final DeviceTokenService deviceTokenService;
    private final PushSender pushSender;
    private final RecordDateCalculator recordDates;
    private final ReminderProperties reminderProps;
    private final TodoProperties props;

    public TodoDispatcher(TodoDispatchQueries queries, DeviceTokenService deviceTokenService, PushSender pushSender,
                          RecordDateCalculator recordDates, ReminderProperties reminderProps, TodoProperties props) {
        this.queries = queries;
        this.deviceTokenService = deviceTokenService;
        this.pushSender = pushSender;
        this.recordDates = recordDates;
        this.reminderProps = reminderProps;
        this.props = props;
    }

    /** 지금 보낼 회차를 모두 처리한다. */
    public void dispatchDue() {
        Instant now = recordDates.now();
        Instant from = now.minus(reminderProps.catchUpOrDefault()); // 제외
        // 하루 전 알림은 예정 날짜 -1일의 기록 날짜에 울리므로 상한은 현재 기록 날짜 +1
        LocalDate fromDate = recordDates.recordDateOf(from);
        LocalDate toDate = recordDates.recordDateOf(now).plusDays(1);
        for (Candidate c : queries.findCandidates(fromDate, toDate)) {
            dispatchKind(c, TodoDispatch.DAY_BEFORE, from, now);
            dispatchKind(c, TodoDispatch.DUE_DAY, from, now);
        }
    }

    private void dispatchKind(Candidate c, String kind, Instant fromExclusive, Instant toInclusive) {
        Instant fireAt = fireAt(c.dueDate(), kind);
        if (!fireAt.isAfter(fromExclusive) || fireAt.isAfter(toInclusive)) {
            return;
        }
        try {
            dispatchSlot(c, kind, fireAt);
        } catch (RuntimeException e) {
            log.error("할 일 알림 처리 실패: todoId={}, kind={}, dueDate={}", c.todoId(), kind, c.dueDate(), e);
        }
    }

    /** 종류별 발송 시각. 하루 전 = (예정 날짜 - 1일) 의 dayBeforeTime, 당일 = 예정 날짜의 dueDayTime */
    Instant fireAt(LocalDate dueDate, String kind) {
        return TodoDispatch.DAY_BEFORE.equals(kind)
                ? recordDates.slotInstant(dueDate.minusDays(1), props.dayBeforeTime())
                : recordDates.slotInstant(dueDate, props.dueDayTime());
    }

    private void dispatchSlot(Candidate c, String kind, Instant fireAt) {
        // 소급 발송 방지: 20:05 에 "내일 일정"을 등록·수정했다면 20:00 알림은 보내지 않는다
        if (fireAt.isBefore(c.updatedAt())) {
            return;
        }
        Optional<Long> claimed = queries.claim(c.userId(), c.todoId(), kind, c.dueDate(), fireAt);
        if (claimed.isEmpty()) {
            return; // 이미 선점된 회차(이전 tick 또는 다른 인스턴스)
        }
        long dispatchId = claimed.get();

        if (!queries.isStillPending(c.userId(), c.todoId(), c.dueDate())) {
            queries.finish(dispatchId, TodoDispatch.SKIPPED_DONE, 0, 0);
            return;
        }
        List<String> tokens = deviceTokenService.tokensOf(c.userId());
        if (tokens.isEmpty()) {
            queries.finish(dispatchId, TodoDispatch.NO_DEVICE, 0, 0);
            return;
        }

        List<PushResult> results;
        try {
            results = pushSender.send(tokens, message(c, kind));
        } catch (RuntimeException e) {
            queries.finish(dispatchId, TodoDispatch.FAILED, 0, tokens.size());
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
        queries.finish(dispatchId, success > 0 ? TodoDispatch.SENT : TodoDispatch.FAILED,
                success, results.size() - success);
        deviceTokenService.removeInvalid(c.userId(), invalid);
    }

    /**
     * 앞뒤 공백을 지우고 코드 포인트 max 자를 넘으면 앞 max-1자 + "…" 로 줄인다(null 은 빈 문자열).
     * 투약 알림(ReminderDispatcher.truncate)과 같은 규칙이다.
     */
    static String truncate(String s, int max) {
        String t = s == null ? "" : s.strip();
        if (t.codePointCount(0, t.length()) <= max) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, max - 1)) + ELLIPSIS;
    }

    /** 본문 "{반려동물} · {제목}" (+ " · 14:30" 시각이 있을 때). 반려동물 10자·제목 20자 말줄임. */
    static String body(String petName, String title, java.time.LocalTime dueTime) {
        String body = truncate(petName, PET_NAME_MAX) + SEPARATOR + truncate(title, TITLE_MAX);
        return dueTime == null ? body : body + SEPARATOR + TimeOfDay.format(dueTime);
    }

    static String title(String kind) {
        return TodoDispatch.DAY_BEFORE.equals(kind) ? TITLE_DAY_BEFORE : TITLE_DUE_DAY;
    }

    /** data 는 문자열만, 제목·메모는 싣지 않는다(ID·날짜·종류·시각만) */
    static PushMessage message(Candidate c, String kind) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", DATA_TYPE);
        data.put("todoId", c.todoId().toString());
        data.put("petId", c.petId().toString());
        data.put("dueDate", c.dueDate().toString());
        data.put("kind", kind);
        if (c.dueTime() != null) {
            data.put("dueTime", TimeOfDay.format(c.dueTime()));
        }
        String collapseKey = "todo:" + c.todoId() + ":" + kind + ":" + c.dueDate();
        return new PushMessage(title(kind), body(c.petName(), c.title(), c.dueTime()),
                Collections.unmodifiableMap(data), collapseKey);
    }
}
