-- =====================================================================
-- 시니어펫 노트 — RLS(행 수준 보안) 정책
--
-- 원칙
--  - 모든 테이블 RLS 활성화. 정책이 없는 동작은 전부 거부된다.
--  - 로그인 사용자(authenticated)만 대상. 비로그인(anon)은 아무것도 못 한다.
--  - 조건은 항상 user_id = auth.uid().
--    (select auth.uid()) 형태는 Supabase 권장 방식으로, 행마다 함수를
--    다시 호출하지 않아 대량 조회 시 빠르다.
--  - UPDATE 는 using + with check 를 모두 걸어 user_id 를 남의 것으로
--    바꾸는 것도 막는다.
--  - service_role(Edge Function 의 푸시 발송 등)은 RLS 를 우회한다.
--    service_role 키는 절대 브라우저 코드에 넣지 않는다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- RLS 활성화
-- ---------------------------------------------------------------------
alter table public.pets               enable row level security;
alter table public.medications        enable row level security;
alter table public.med_logs           enable row level security;
alter table public.daily_logs         enable row level security;
alter table public.push_subscriptions enable row level security;
alter table public.events             enable row level security;

-- ---------------------------------------------------------------------
-- 권한(GRANT) 정리 — RLS 와 별개로 한 겹 더 막는다
-- Supabase 는 기본적으로 anon/authenticated 에 public 테이블 권한을 준다.
-- ---------------------------------------------------------------------
revoke all on public.pets, public.medications, public.med_logs,
              public.daily_logs, public.push_subscriptions, public.events
  from anon;

-- events 는 추가만 허용
revoke all on public.events from authenticated;
grant insert on public.events to authenticated;

-- ---------------------------------------------------------------------
-- pets
-- ---------------------------------------------------------------------
create policy "pets: 본인 조회" on public.pets
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "pets: 본인 추가" on public.pets
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "pets: 본인 수정" on public.pets
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "pets: 본인 삭제" on public.pets
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------
-- medications
-- ---------------------------------------------------------------------
create policy "medications: 본인 조회" on public.medications
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "medications: 본인 추가" on public.medications
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "medications: 본인 수정" on public.medications
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "medications: 본인 삭제" on public.medications
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------
-- med_logs
-- ---------------------------------------------------------------------
create policy "med_logs: 본인 조회" on public.med_logs
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "med_logs: 본인 추가" on public.med_logs
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "med_logs: 본인 수정" on public.med_logs
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "med_logs: 본인 삭제" on public.med_logs
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------
-- daily_logs
-- ---------------------------------------------------------------------
create policy "daily_logs: 본인 조회" on public.daily_logs
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "daily_logs: 본인 추가" on public.daily_logs
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "daily_logs: 본인 수정" on public.daily_logs
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "daily_logs: 본인 삭제" on public.daily_logs
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------
-- push_subscriptions
-- ---------------------------------------------------------------------
create policy "push_subscriptions: 본인 조회" on public.push_subscriptions
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "push_subscriptions: 본인 추가" on public.push_subscriptions
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "push_subscriptions: 본인 수정" on public.push_subscriptions
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy "push_subscriptions: 본인 삭제" on public.push_subscriptions
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- ---------------------------------------------------------------------
-- events — 추가만 가능 (조회·수정·삭제 정책 없음 = 거부)
-- ---------------------------------------------------------------------
create policy "events: 본인 추가" on public.events
  for insert to authenticated
  with check ((select auth.uid()) = user_id);
