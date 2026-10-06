-- =====================================================================
-- 20261006000004_record_date_comment.sql
-- 기록 날짜 규칙(새벽 4시 기준) 명시 — 스키마 변경 없음, 주석만 갱신
-- 근거: docs/decisions/2026-10-06-MVP-세부-결정.md 결정 3
--
-- 규칙: Asia/Seoul 기준 00:00~03:59 에 체크한 투약은 "전날" record_date 로 저장한다.
--       04:00 이후 체크는 당일. 실제 체크 시각은 taken_at 에 그대로 남긴다.
-- 계산은 앱(app/src/lib/recordDate.ts 의 toRecordDate, 상수 RECORD_DAY_CUTOFF_HOUR = 4)이 한다.
-- 규칙이 바뀌어도 taken_at 으로 다시 계산할 수 있다.
-- =====================================================================

comment on column public.med_logs.record_date is
  '기록 날짜(어느 날의 약인지). 규칙: Asia/Seoul 기준 새벽 4시 전(00:00~03:59) 체크는 전날 날짜, 04:00 이후는 당일. 앱이 toRecordDate(taken_at)로 계산해 넣는다. 실제 체크 시각은 taken_at.';

comment on column public.med_logs.taken_at is
  '실제 체크 시각(timestamptz). record_date 규칙이 바뀌면 이 값으로 다시 계산한다.';

comment on column public.daily_logs.record_date is
  '기록 날짜. 앱은 투약과 같은 규칙(Asia/Seoul 기준 새벽 4시 전은 전날)으로 "오늘" 화면 날짜를 정해 넣는다(개발자 제안, 대표 확인 대상).';
