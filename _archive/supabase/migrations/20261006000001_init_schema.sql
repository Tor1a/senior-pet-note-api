-- =====================================================================
-- 시니어펫 노트 — 초기 스키마 (테이블, 제약조건, 인덱스)
-- 대상: Supabase (Postgres 15 이상)
-- 근거: projects/senior-pet-note/PLAN.md 4장 "데이터 모델 (최소)"
--
-- 설계 원칙
--  1) 모든 테이블에 user_id 를 둔다. RLS 정책을 "user_id = auth.uid()" 한 줄로
--     단순하게 유지하고, 조인 없이 빠르게 검사하기 위함이다.
--  2) 자식 테이블은 (부모 id, user_id) 복합 외래키로 부모를 참조한다.
--     => 남의 반려동물/약에 내 기록을 붙이는 것이 DB 차원에서 불가능하다.
--  3) auth.users 삭제(회원 탈퇴) 시 모든 데이터가 함께 삭제된다(on delete cascade).
--  4) 해석·판단 컬럼(예: "이상 여부")은 두지 않는다. 기록만 저장한다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 공통: updated_at 자동 갱신 트리거 함수
-- ---------------------------------------------------------------------
create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------
-- 1. pets — 반려동물 프로필 (M2)
-- ---------------------------------------------------------------------
create table public.pets (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid()
              references auth.users (id) on delete cascade,
  name        text not null check (char_length(btrim(name)) between 1 and 30),
  species     text not null check (species in ('dog', 'cat')),
  birth_year  smallint check (birth_year between 1980 and 2100),
  conditions  text check (char_length(conditions) <= 200),      -- 질환(자유 입력)
  photo_path  text check (char_length(photo_path) <= 300),      -- Storage 객체 경로(선택)
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  -- 자식 테이블의 복합 외래키 대상
  constraint pets_id_user_key unique (id, user_id)
);

comment on table public.pets is '반려동물 프로필. MVP는 사용자당 1마리(pets_one_per_user 인덱스로 제한).';

-- MVP 제한: 사용자당 1마리.
-- 2개월 차 "여러 마리 관리" 도입 시 아래 한 줄로 해제한다.
--   drop index public.pets_one_per_user;  (그리고 일반 인덱스 pets_user_id_idx 생성)
create unique index pets_one_per_user on public.pets (user_id);

create trigger pets_set_updated_at
  before update on public.pets
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------
-- 2. medications — 투약 일정 (M3)
--    times: 하루 투약 시각(보호자 현지 시각, 최대 3개)
-- ---------------------------------------------------------------------
create table public.medications (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid()
              references auth.users (id) on delete cascade,
  pet_id      uuid not null,
  name        text not null check (char_length(btrim(name)) between 1 and 50),
  dose_text   text check (char_length(dose_text) <= 50),        -- 예: "1/2정", "0.5ml"
  times       time[] not null
              check (cardinality(times) between 1 and 3
                     and array_position(times, null) is null),
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint medications_id_user_key unique (id, user_id),
  constraint medications_pet_fk foreign key (pet_id, user_id)
    references public.pets (id, user_id) on delete cascade
);

comment on table public.medications is '투약 일정. times 는 보호자 현지 시각(time[]), 최대 3개.';

create index medications_user_active_idx on public.medications (user_id) where active;
create index medications_pet_id_idx     on public.medications (pet_id);

create trigger medications_set_updated_at
  before update on public.medications
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------
-- 3. med_logs — 투약 체크 (M3, M6 누락 표시, M7 준수율)
--    투약 카드를 누르는 즉시 1행씩 개별 저장한다([저장] 버튼과 무관).
--    회차 식별 = record_date(기록 날짜) + scheduled_time(일정 시각)
--      record_date   : 이 체크를 "어느 날의 약"으로 볼지. 앱이 정해서 넣는다.
--                      자정을 넘긴 밤 약의 날짜 규칙(와이어프레임 7장 질문 3)이
--                      아직 미정이므로, 실제 시각과 분리해 저장한다.
--      scheduled_time: medications.times 중 어느 시각의 회차인지
--      taken_at      : 실제로 체크한 시각(timestamptz)
--    규칙이 정해진 뒤에도 taken_at 이 남아 있으므로 날짜를 다시 계산할 수 있다.
--    체크 취소 = 행 삭제. 같은 회차 중복 체크는 unique 로 막는다.
--    unique 에 user_id 를 넣은 이유: 다른 사용자의 데이터 존재 여부가
--    중복 오류로 드러나지 않게 하기 위함(FK 검사보다 unique 검사가 먼저 실행됨).
-- ---------------------------------------------------------------------
create table public.med_logs (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid()
                  references auth.users (id) on delete cascade,
  medication_id   uuid not null,
  record_date     date not null,
  scheduled_time  time not null,
  taken_at        timestamptz not null default now(),
  created_at      timestamptz not null default now(),
  constraint med_logs_once_per_slot
    unique (user_id, medication_id, record_date, scheduled_time),
  constraint med_logs_medication_fk foreign key (medication_id, user_id)
    references public.medications (id, user_id) on delete cascade
);

comment on table public.med_logs is '투약 체크 기록. 회차(record_date + scheduled_time)당 1건, 체크 취소는 삭제.';
comment on column public.med_logs.record_date is '기록 날짜(어느 날의 약인지). 실제 체크 시각은 taken_at.';

-- 오늘/30일 범위 조회용
create index med_logs_user_date_idx on public.med_logs (user_id, record_date desc);

-- ---------------------------------------------------------------------
-- 4. daily_logs — 일일 기록 (M5, M6, M7)
--    하루 1행(user_id + pet_id + record_date unique).
--    앱은 upsert(onConflict: "user_id,pet_id,record_date") 로 저장한다.
--    unique 에 user_id 를 넣은 이유: 다른 사용자의 기록 존재 여부가
--    중복 오류로 드러나지 않게 하기 위함(FK 검사보다 unique 검사가 먼저 실행됨).
--    [저장] 버튼으로 식사·물·증상·체중·메모를 한 번에 저장한다.
--    저장하지 않은 날은 행이 없다(= "기록 없음"). 빈 값을 0이나 "보통"으로 채우지 않는다.
--    모든 측정 항목은 nullable: null = 그 항목은 안 적음.
--    - 식사 food_level   : 1=조금, 2=보통, 3=많이 (평소와 비교)
--    - 음수 water_level  : 1=조금, 2=보통, 3=많이 (선택형)
--           water_ml     : ml 직접 입력(선택). 단계와 별도 컬럼이라 그래프에서 섞이지 않는다.
--    - 체중 weight_kg    : "오늘 쟀어요"를 누르거나 값을 바꾼 날만 저장, 아니면 null
--    - 증상 symptoms     : 선택한 태그 코드 배열
--             vomit(구토) diarrhea(설사) cough(기침) lethargy(기운 없음)
--             seizure(발작) other(기타 — 내용은 symptom_other, 30자)
--           symptoms_none: "특이사항 없음"을 사용자가 명시적으로 선택했으면 true
--           => 증상 없음 = symptoms_none = true 이고 symptoms = '{}'
--              안 적음   = symptoms_none = false 이고 symptoms = '{}'
--              증상 있음 = symptoms 에 1개 이상 (이때 symptoms_none 은 반드시 false)
-- ---------------------------------------------------------------------
create table public.daily_logs (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  pet_id       uuid not null,
  record_date  date not null,                                  -- 기록 날짜(보호자 기기 로컬 날짜)
  weight_kg    numeric(5, 2) check (weight_kg > 0 and weight_kg < 200),
  water_ml     integer      check (water_ml between 0 and 20000),
  water_level  smallint     check (water_level between 1 and 3),
  food_level   smallint     check (food_level between 1 and 3),
  symptoms     text[] not null default '{}'
               check (symptoms <@ array['vomit', 'diarrhea', 'cough',
                                        'lethargy', 'seizure', 'other']::text[]),
  symptoms_none boolean not null default false,
  symptom_other text check (char_length(symptom_other) <= 30),
  memo         text check (char_length(memo) <= 1000),       -- 화면 입력 제한은 200자(여유분 포함)
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  -- "특이사항 없음"과 증상 태그는 동시에 선택될 수 없다
  constraint daily_logs_symptoms_none_exclusive
    check (not (symptoms_none and cardinality(symptoms) > 0)),
  -- 기타 내용은 'other' 태그를 고른 경우에만
  constraint daily_logs_symptom_other_needs_tag
    check (symptom_other is null or 'other' = any (symptoms)),
  constraint daily_logs_one_per_day unique (user_id, pet_id, record_date),
  constraint daily_logs_pet_fk foreign key (pet_id, user_id)
    references public.pets (id, user_id) on delete cascade
);

comment on table public.daily_logs is '일일 기록. 반려동물당 하루 1행. 해석·판단 값은 저장하지 않는다.';
comment on column public.daily_logs.symptoms_none is '사용자가 "특이사항 없음"을 명시적으로 선택했으면 true. false + 빈 배열 = 안 적음.';
comment on column public.daily_logs.water_level is '음수 선택형 1=조금 2=보통 3=많이. null = 안 적음.';
comment on column public.daily_logs.water_ml is '음수 ml 직접 입력(선택). null = 안 적음.';
comment on column public.daily_logs.weight_kg is '"오늘 쟀어요" 누른 날만 저장. null = 안 잼.';

-- 30일 그래프·리포트 조회용
create index daily_logs_user_date_idx on public.daily_logs (user_id, record_date desc);

create trigger daily_logs_set_updated_at
  before update on public.daily_logs
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------
-- 5. push_subscriptions — 웹 푸시 구독 (M4)
--    브라우저 PushSubscription.toJSON() 의 endpoint, keys.p256dh, keys.auth 저장.
--    timezone: 발송 함수(3주 차 Edge Function)가 medications.times 를
--              실제 시각으로 바꿀 때 사용. 기본값 Asia/Seoul.
--    같은 기기에서 다른 계정으로 로그인할 수 있으므로 unique 는 (user_id, endpoint).
-- ---------------------------------------------------------------------
create table public.push_subscriptions (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null default auth.uid()
                references auth.users (id) on delete cascade,
  endpoint      text not null check (endpoint like 'https://%' and char_length(endpoint) <= 1000),
  p256dh        text not null check (char_length(p256dh) <= 200),
  auth_key      text not null check (char_length(auth_key) <= 100),
  timezone      text not null default 'Asia/Seoul' check (char_length(timezone) <= 64),
  user_agent    text check (char_length(user_agent) <= 300),
  created_at    timestamptz not null default now(),
  last_used_at  timestamptz,
  constraint push_subscriptions_user_endpoint_key unique (user_id, endpoint)
);

comment on table public.push_subscriptions is '웹 푸시 구독 정보. 발송은 service_role(Edge Function)이 읽는다.';

-- ---------------------------------------------------------------------
-- 6. events — 지표 측정용 이벤트 (1장 성공 지표)
--    사용자는 자기 이벤트를 "추가"만 할 수 있다(조회·수정·삭제 불가).
--    집계는 대표가 대시보드(SQL 편집기, postgres 권한)에서 수행한다.
-- ---------------------------------------------------------------------
create table public.events (
  id          bigint generated always as identity primary key,
  user_id     uuid not null default auth.uid()
              references auth.users (id) on delete cascade,
  name        text not null check (name ~ '^[a-z][a-z0-9_]{1,49}$'),  -- 예: signup, first_log, report_created
  props       jsonb not null default '{}'::jsonb
              check (jsonb_typeof(props) = 'object' and pg_column_size(props) <= 2000),
  created_at  timestamptz not null default now()
);

comment on table public.events is '지표 이벤트(가입, 첫 기록, 리포트 생성 등). 개인정보를 props 에 넣지 않는다.';

create index events_user_created_idx on public.events (user_id, created_at desc);
create index events_name_created_idx on public.events (name, created_at desc);
