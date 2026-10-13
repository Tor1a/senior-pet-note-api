-- =====================================================================
-- 오래곁 — 내 할 일 일정 + 하루 전/당일 알림 발송 기록 (Flyway V4)
-- 근거: 기능 기획서 "내 할 일 일정" 5장, docs/api-todos.md
--
-- V1·V2 의 설계 원칙을 그대로 따른다.
--  1) 모든 테이블에 user_id. 사용자 API 는 user_id 조건으로 "본인 데이터만" 다룬다(README 보안 규칙).
--  2) 부모는 (부모 id, user_id) 복합 외래키로 참조한다.
--  3) users 삭제 시 함께 삭제(on delete cascade).
--  4) updated_at 은 V1 의 set_updated_at() 트리거로 갱신한다.
-- 서비스는 일정·주기를 정해 주거나 추천하지 않는다. 이 테이블에는 보호자가 입력한 값만 들어간다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. todos — 보호자가 직접 입력한 할 일(접종·구충·검진 등)
-- ---------------------------------------------------------------------
create table todos (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users (id) on delete cascade,
  pet_id         uuid not null,
  title          text not null check (char_length(btrim(title)) between 1 and 40),
  due_date       date not null,
  due_time       time,
  memo           text check (char_length(memo) <= 200),
  hospital_note  text check (char_length(hospital_note) <= 500),
  remind         boolean not null default true,
  completed_at   timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  -- 복합 외래키(todo_dispatches)의 대상
  constraint todos_id_user_key unique (id, user_id),
  constraint todos_pet_fk foreign key (pet_id, user_id)
    references pets (id, user_id) on delete cascade
);

comment on table todos is
  '보호자가 직접 입력한 할 일. 서비스가 일정·주기를 정하거나 추천하지 않는다. 사용자당 최대 300개(API 에서 검사).';
comment on column todos.title is '제목(앞뒤 공백 제외 1~40자). 보호자 입력값 그대로.';
comment on column todos.due_date is '예정 날짜. 기록 날짜 체계(새벽 4시 규칙)의 날짜 — 00:00~03:59 에는 "오늘" = 전날 날짜.';
comment on column todos.due_time is '예정 시각(선택, 현지 시각 HH:mm). 알림 발송 시각과는 무관하며 알림 본문에 보여 줄 뿐이다.';
comment on column todos.memo is '일정 메모(선택, 200자 이하).';
comment on column todos.hospital_note is '병원 메모(선택, 500자 이하). 방문 후 보호자가 적는 자유 메모. 푸시·리포트·이벤트에 싣지 않는다.';
comment on column todos.remind is 'true = 하루 전·당일 알림을 보낸다.';
comment on column todos.completed_at is '완료 시각. null = 미완료. 완료하면 알림을 보내지 않는다.';

create index todos_user_due_idx on todos (user_id, due_date);
-- 발송 작업의 후보 조회용(알림 켬 + 미완료)
create index todos_dispatch_idx on todos (due_date) where remind and completed_at is null;

create trigger todos_set_updated_at
  before update on todos
  for each row execute function set_updated_at();

-- ---------------------------------------------------------------------
-- 2. todo_dispatches — 할 일 알림 발송 기록 (중복 방지 겸 감사 로그)
--    발송 작업은 먼저 이 테이블에 insert ... on conflict do nothing 으로 회차를 선점(claimed)하고,
--    선점에 성공한 경우에만 발송한다 => 여러 인스턴스가 동시에 돌아도 (할 일, 종류, 날짜)당 최대 1회.
--    status: claimed(선점 후 결과 반영 전) / sent / failed / skipped_done(선점 뒤 완료됨) / no_device(기기 없음)
-- ---------------------------------------------------------------------
create table todo_dispatches (
  id             bigint generated always as identity primary key,
  user_id        uuid not null references users (id) on delete cascade,
  todo_id        uuid not null,
  kind           text not null check (kind in ('day_before', 'due_day')),
  due_date       date not null,
  fire_at        timestamptz not null,
  status         text not null
                 check (status in ('claimed', 'sent', 'failed', 'skipped_done', 'no_device')),
  success_count  smallint not null default 0,
  failure_count  smallint not null default 0,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint todo_dispatches_once_per_slot unique (todo_id, kind, due_date),
  constraint todo_dispatches_todo_fk foreign key (todo_id, user_id)
    references todos (id, user_id) on delete cascade
);

comment on table todo_dispatches is
  '할 일 알림 발송 기록. (할 일, 종류, 예정 날짜)당 1행(유니크 키가 중복 발송 방지의 근거). 보관 기간 무기한(MVP).';
comment on column todo_dispatches.kind is 'day_before = 예정 날짜 하루 전 알림 / due_day = 당일 알림.';
comment on column todo_dispatches.due_date is '발송 시점의 todos.due_date 스냅샷(기록 날짜 체계). 날짜를 고치면 새 날짜로 다시 알릴 수 있다.';
comment on column todo_dispatches.fire_at is '예정 발송 시각.';
comment on column todo_dispatches.status is
  'claimed=선점(발송 중 서버가 죽으면 이 상태로 남고 재발송하지 않음) / sent / failed / skipped_done / no_device';

create index todo_dispatches_created_at_idx on todo_dispatches (created_at);

create trigger todo_dispatches_set_updated_at
  before update on todo_dispatches
  for each row execute function set_updated_at();
