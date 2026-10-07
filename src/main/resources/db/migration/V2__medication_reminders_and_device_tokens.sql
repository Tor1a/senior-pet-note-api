-- =====================================================================
-- 시니어펫 노트 — 투약 알림 + FCM 기기 토큰 (Flyway V2)
-- 근거: 기능 기획서 "투약 알림 스케줄 API + FCM 푸시 발송" 5장, docs/api-reminders.md
--
-- V1 의 설계 원칙을 그대로 따른다.
--  1) 모든 테이블에 user_id. 사용자 API 는 user_id 조건으로 "본인 데이터만" 다룬다(README 보안 규칙).
--  2) 부모는 (부모 id, user_id) 복합 외래키로 참조한다.
--  3) users 삭제 시 함께 삭제(on delete cascade).
--  4) updated_at 은 V1 의 set_updated_at() 트리거로 갱신한다.
-- medications 테이블과 Medication API 는 바꾸지 않는다(docs/api-today.md 계약 동결).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. medication_reminders — 약별 알림 규칙 (medications 와 1:1)
--    알림 시각은 따로 두지 않고 medications.times 를 그대로 쓴다.
--    반복 판정 기준은 기록 날짜(새벽 4시 규칙)다. 04:00 전 시각의 회차는 기록 날짜 다음 날 그 시각에 발송한다.
-- ---------------------------------------------------------------------
create table medication_reminders (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users (id) on delete cascade,
  medication_id  uuid not null,
  enabled        boolean not null default true,
  repeat_type    text not null check (repeat_type in ('daily', 'weekly', 'interval')),
  -- ISO 요일: 1=월 … 7=일
  days_of_week   smallint[] not null default '{}'
                 check (days_of_week <@ array[1, 2, 3, 4, 5, 6, 7]::smallint[]
                        and array_position(days_of_week, null) is null),
  interval_days  smallint check (interval_days between 2 and 30),
  start_date     date not null,
  end_date       date,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  -- 약 1개당 규칙 1개
  constraint medication_reminders_medication_key unique (medication_id),
  constraint medication_reminders_medication_fk foreign key (medication_id, user_id)
    references medications (id, user_id) on delete cascade,
  constraint medication_reminders_end_after_start
    check (end_date is null or end_date >= start_date),
  -- weekly 이면 요일 1~7개, 그 외에는 빈 배열
  constraint medication_reminders_weekly_days
    check (case when repeat_type = 'weekly' then cardinality(days_of_week) between 1 and 7
                else cardinality(days_of_week) = 0 end),
  -- interval 이면 간격 필수, 그 외에는 null
  constraint medication_reminders_interval_days
    check ((repeat_type = 'interval') = (interval_days is not null))
);

comment on table medication_reminders is
  '약별 알림 규칙(약 1개당 1개). 알림 시각은 medications.times 를 쓴다. 약이 비활성(active=false)이면 발송하지 않는다.';
comment on column medication_reminders.enabled is 'false = 알림 끔(규칙은 보존).';
comment on column medication_reminders.days_of_week is 'weekly 일 때 요일(ISO 1=월 … 7=일). 그 외에는 빈 배열.';
comment on column medication_reminders.interval_days is 'interval 일 때 N일 간격(2~30). start_date 를 기준일로 (기록 날짜 - start_date) % N = 0 인 날 발송.';
comment on column medication_reminders.start_date is '기록 날짜 기준 시작일(포함). interval 의 기준일.';
comment on column medication_reminders.end_date is '기록 날짜 기준 종료일(포함). null = 종료 없음.';

create index medication_reminders_user_id_idx on medication_reminders (user_id);
create index medication_reminders_enabled_idx on medication_reminders (medication_id) where enabled;

create trigger medication_reminders_set_updated_at
  before update on medication_reminders
  for each row execute function set_updated_at();

-- ---------------------------------------------------------------------
-- 2. device_tokens — FCM 기기 등록 토큰 (Android·iOS·웹 공통)
--    token 은 전역 unique: 한 기기에서 사용자 A 가 해제 없이 로그아웃한 뒤 B 가 로그인해 등록하면
--    A 의 행을 지우고 B 로 다시 만든다(A 의 알림이 B 의 기기로 가지 않게).
--    사용자당 최대 10개(앱이 관리). 넘치면 last_seen_at 이 가장 오래된 것부터 지운다.
-- ---------------------------------------------------------------------
create table device_tokens (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references users (id) on delete cascade,
  token         text not null check (char_length(token) between 1 and 4096),
  platform      text not null check (platform in ('android', 'ios', 'web')),
  created_at    timestamptz not null default now(),
  last_seen_at  timestamptz not null default now(),
  constraint device_tokens_token_key unique (token)
);

comment on table device_tokens is
  'FCM 기기 토큰. 토큰 값은 API 응답에 다시 내보내지 않는다. FCM 이 무효(UNREGISTERED 등)라고 알려 주면 삭제한다.';
comment on column device_tokens.platform is '통계·디버깅용(android|ios|web). 발송 로직은 공통.';
comment on column device_tokens.last_seen_at is '마지막 등록(재등록) 시각. 사용자당 개수 초과 시 가장 오래된 것부터 삭제.';

create index device_tokens_user_id_idx on device_tokens (user_id);

-- ---------------------------------------------------------------------
-- 3. reminder_dispatches — 알림 발송 기록 (중복 방지 겸 감사 로그)
--    회차 = (user_id, medication_id, record_date, scheduled_time). med_logs_once_per_slot 과 같은 키.
--    발송 작업은 먼저 이 테이블에 insert ... on conflict do nothing 으로 회차를 선점(claimed)하고,
--    선점에 성공한 경우에만 발송한다 => 여러 인스턴스가 동시에 돌아도 회차당 최대 1회.
--    status: claimed(선점 후 결과 반영 전) / sent / failed / skipped_taken(이미 투약 체크) / no_device(기기 없음)
-- ---------------------------------------------------------------------
create table reminder_dispatches (
  id              bigint generated always as identity primary key,
  user_id         uuid not null references users (id) on delete cascade,
  medication_id   uuid not null,
  record_date     date not null,
  scheduled_time  time not null,
  fire_at         timestamptz not null,
  status          text not null
                  check (status in ('claimed', 'sent', 'failed', 'skipped_taken', 'no_device')),
  success_count   smallint not null default 0,
  failure_count   smallint not null default 0,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint reminder_dispatches_once_per_slot
    unique (user_id, medication_id, record_date, scheduled_time),
  constraint reminder_dispatches_medication_fk foreign key (medication_id, user_id)
    references medications (id, user_id) on delete cascade
);

comment on table reminder_dispatches is
  '투약 알림 발송 기록. 회차당 1행(유니크 키가 중복 발송 방지의 근거). 보관 기간 무기한(MVP).';
comment on column reminder_dispatches.record_date is '어느 날의 회차인지(기록 날짜, 새벽 4시 규칙. med_logs.record_date 와 같은 의미).';
comment on column reminder_dispatches.fire_at is '예정 발송 시각. 04:00 전 시각의 회차는 record_date 다음 날 그 시각.';
comment on column reminder_dispatches.status is
  'claimed=선점(발송 중 서버가 죽으면 이 상태로 남고 재발송하지 않음) / sent / failed / skipped_taken / no_device';

-- 추후 보관 기간 정리 작업용
create index reminder_dispatches_created_at_idx on reminder_dispatches (created_at);

create trigger reminder_dispatches_set_updated_at
  before update on reminder_dispatches
  for each row execute function set_updated_at();
