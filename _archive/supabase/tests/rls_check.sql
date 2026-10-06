-- =====================================================================
-- 시니어펫 노트 — RLS 점검 스크립트 ("다른 계정의 데이터 조회 불가" 확인)
--
-- 사용법: 마이그레이션 적용 후, Supabase 대시보드 SQL Editor 에 전체를
--         붙여 넣고 Run. 모든 작업은 마지막에 ROLLBACK 되므로 데이터가
--         남지 않는다.
-- 결과:   성공하면 마지막에 "RLS 점검 통과" 가 출력된다.
--         실패하면 "RLS 점검 실패: ..." 오류로 멈춘다.
-- =====================================================================

begin;

-- 테스트용 사용자 2명 (트랜잭션 종료 시 롤백됨)
insert into auth.users (id, email) values
  ('00000000-0000-4000-a000-00000000000a', 'rls-test-a@example.invalid'),
  ('00000000-0000-4000-a000-00000000000b', 'rls-test-b@example.invalid');

-- ---------------------------------------------------------------
-- 사용자 A 로 로그인한 상태를 흉내 내고 데이터 생성
-- ---------------------------------------------------------------
set local role authenticated;
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-4000-a000-00000000000a","role":"authenticated"}', true);

insert into public.pets (id, name, species, birth_year, conditions)
values ('00000000-0000-4000-b000-0000000000a1', '콩이', 'dog', 2012, '신부전');

insert into public.medications (id, pet_id, name, dose_text, times)
values ('00000000-0000-4000-c000-0000000000a1', '00000000-0000-4000-b000-0000000000a1',
        '베나제프릴', '1/2정', array['08:00', '20:00']::time[]);

insert into public.med_logs (medication_id, record_date, scheduled_time)
values ('00000000-0000-4000-c000-0000000000a1', '2026-10-06', '08:00');

insert into public.daily_logs (pet_id, record_date, weight_kg, water_level, food_level, symptoms, memo)
values ('00000000-0000-4000-b000-0000000000a1', '2026-10-06', 5.2, 2, 3, array['vomit'], '아침에 1회');

insert into public.push_subscriptions (endpoint, p256dh, auth_key)
values ('https://push.example.invalid/abc', 'p256dh-test', 'auth-test');

insert into public.events (name) values ('first_log');

do $$
declare n int;
begin
  -- A 는 자기 데이터를 볼 수 있어야 한다
  select count(*) into n from public.pets;               if n <> 1 then raise exception 'RLS 점검 실패: A 가 자기 pets 를 못 봄'; end if;
  select count(*) into n from public.medications;        if n <> 1 then raise exception 'RLS 점검 실패: A 가 자기 medications 를 못 봄'; end if;
  select count(*) into n from public.med_logs;           if n <> 1 then raise exception 'RLS 점검 실패: A 가 자기 med_logs 를 못 봄'; end if;
  select count(*) into n from public.daily_logs;         if n <> 1 then raise exception 'RLS 점검 실패: A 가 자기 daily_logs 를 못 봄'; end if;
  select count(*) into n from public.push_subscriptions; if n <> 1 then raise exception 'RLS 점검 실패: A 가 자기 push_subscriptions 를 못 봄'; end if;

  -- MVP: 1마리 제한
  begin
    insert into public.pets (name, species) values ('두번째', 'cat');
    raise exception 'RLS 점검 실패: 두 번째 반려동물이 등록됨';
  exception when unique_violation then null;
  end;

  -- 데이터 규칙: "특이사항 없음"과 증상 태그 동시 저장 불가
  begin
    insert into public.daily_logs (pet_id, record_date, symptoms, symptoms_none)
    values ('00000000-0000-4000-b000-0000000000a1', '2026-10-05', array['cough'], true);
    raise exception 'RLS 점검 실패: 특이사항 없음 + 증상 태그가 함께 저장됨';
  exception when check_violation then null;
  end;

  -- 데이터 규칙: 같은 회차 투약 중복 체크 불가
  begin
    insert into public.med_logs (medication_id, record_date, scheduled_time)
    values ('00000000-0000-4000-c000-0000000000a1', '2026-10-06', '08:00');
    raise exception 'RLS 점검 실패: 같은 회차 투약이 두 번 저장됨';
  exception when unique_violation then null;
  end;

  -- events 는 추가만 가능, 조회 불가
  begin
    perform 1 from public.events;
    raise exception 'RLS 점검 실패: events 조회가 허용됨';
  exception when insufficient_privilege then null;
  end;
end $$;

-- ---------------------------------------------------------------
-- 사용자 B 로 전환 — A 의 데이터가 보이거나 바뀌면 실패
-- ---------------------------------------------------------------
select set_config('request.jwt.claims',
  '{"sub":"00000000-0000-4000-a000-00000000000b","role":"authenticated"}', true);

do $$
declare n int;
begin
  select count(*) into n from public.pets;               if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 pets 를 봄'; end if;
  select count(*) into n from public.medications;        if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 medications 를 봄'; end if;
  select count(*) into n from public.med_logs;           if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 med_logs 를 봄'; end if;
  select count(*) into n from public.daily_logs;         if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 daily_logs 를 봄'; end if;
  select count(*) into n from public.push_subscriptions; if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 push_subscriptions 를 봄'; end if;

  -- 수정·삭제 시도는 0건이어야 한다
  update public.pets set name = '해킹' where id = '00000000-0000-4000-b000-0000000000a1';
  get diagnostics n = row_count; if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 pets 를 수정함'; end if;
  delete from public.daily_logs;
  get diagnostics n = row_count; if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 daily_logs 를 삭제함'; end if;
  delete from public.med_logs;
  get diagnostics n = row_count; if n <> 0 then raise exception 'RLS 점검 실패: B 가 A 의 med_logs 를 삭제함'; end if;

  -- A 명의(user_id=A)로 데이터 넣기 → RLS 로 거부
  begin
    insert into public.pets (user_id, name, species)
    values ('00000000-0000-4000-a000-00000000000a', '가짜', 'cat');
    raise exception 'RLS 점검 실패: B 가 A 명의로 pets 추가함';
  exception when insufficient_privilege then null;
  end;

  -- 내 명의로 A 의 반려동물에 기록 붙이기 → 복합 외래키로 거부
  begin
    insert into public.daily_logs (pet_id, record_date, weight_kg)
    values ('00000000-0000-4000-b000-0000000000a1', '2026-10-06', 1);
    raise exception 'RLS 점검 실패: B 가 A 의 반려동물에 기록 추가함';
  exception when foreign_key_violation then null;
  end;

  begin
    insert into public.med_logs (medication_id, record_date, scheduled_time)
    values ('00000000-0000-4000-c000-0000000000a1', '2026-10-06', '08:00');
    raise exception 'RLS 점검 실패: B 가 A 의 약에 투약 체크 추가함';
  exception when foreign_key_violation then null;
  end;
end $$;

-- ---------------------------------------------------------------
-- 비로그인(anon) — 아무것도 못 해야 한다
-- ---------------------------------------------------------------
set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);

do $$
begin
  begin
    perform 1 from public.pets;
    raise exception 'RLS 점검 실패: 비로그인 사용자가 pets 조회 가능';
  exception when insufficient_privilege then null;
  end;
  begin
    perform 1 from public.daily_logs;
    raise exception 'RLS 점검 실패: 비로그인 사용자가 daily_logs 조회 가능';
  exception when insufficient_privilege then null;
  end;
  raise notice 'RLS 점검 통과';
end $$;

select 'RLS 점검 통과' as result;

rollback;
