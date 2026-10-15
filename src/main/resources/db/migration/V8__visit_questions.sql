-- =====================================================================
-- 오래곁 — 진료 때 물어볼 것 메모 (Flyway V8)
-- 근거: 기능 기획서 "진료 질문 메모" 3장, docs/api-visit-questions.md
--
-- V4(todos)의 설계 원칙을 그대로 따른다.
--  1) user_id 필수. 사용자 API 는 user_id 조건으로 "본인 데이터만" 다룬다(README 보안 규칙).
--  2) 부모(pets)는 (pet_id, user_id) 복합 외래키로 참조한다.
--  3) users 삭제(탈퇴) 시 함께 삭제(on delete cascade).
--  4) updated_at 은 V1 의 set_updated_at() 트리거로 갱신한다.
-- 서비스는 질문을 만들거나 추천·해석하지 않는다. 이 테이블에는 보호자가 입력한 글만 들어간다(시드 없음).
-- =====================================================================
create table visit_questions (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references users (id) on delete cascade,
  pet_id        uuid not null,
  text          text not null check (char_length(btrim(text)) between 1 and 100),
  completed_at  timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint visit_questions_id_user_key unique (id, user_id),
  constraint visit_questions_pet_fk foreign key (pet_id, user_id)
    references pets (id, user_id) on delete cascade
);

comment on table visit_questions is
  '보호자가 직접 입력한 진료 때 물어볼 것. 건강 관련 내용이 들어갈 수 있다. 푸시·이벤트·로그에 싣지 않는다. 서비스가 해석하지 않는다. 사용자당 최대 30개(API 에서 검사).';
comment on column visit_questions.text is
  '보호자가 직접 입력한 글(앞뒤 공백 제외 1~100자). 건강 관련 내용이 들어갈 수 있다. 푸시·이벤트·로그에 싣지 않는다. 서비스가 해석하지 않는다.';
comment on column visit_questions.completed_at is '물어봤어요 체크 시각. null = 미완료.';

create index visit_questions_user_pet_idx on visit_questions (user_id, pet_id, created_at);

create trigger visit_questions_set_updated_at
  before update on visit_questions
  for each row execute function set_updated_at();
