-- =====================================================================
-- 오래곁 — 일일 기록에 루틴 기록(산책·배변 횟수) 추가 (Flyway V5)
-- 근거: 기능 기획서 "루틴 기록 칩" 6-1
--
-- nullable 컬럼 두 개만 추가한다. 기존 행·기존 쿼리에는 영향이 없다.
-- 기록 날짜(record_date) 규칙(새벽 4시)은 기존 PUT 을 그대로 탄다.
-- 상한 20 은 오입력 방지용 검증이며 권장·기준이 아니다.
-- =====================================================================
alter table daily_logs
  add column walk_count smallint check (walk_count between 0 and 20),
  add column poop_count smallint check (poop_count between 0 and 20);

comment on column daily_logs.walk_count is '산책 횟수. 보호자가 입력한 값만 저장한다. null = 안 적음, 0 = 0회로 입력.';
comment on column daily_logs.poop_count is '배변(대변) 횟수. 상태(색·모양)는 저장하지 않는다. null = 안 적음.';
