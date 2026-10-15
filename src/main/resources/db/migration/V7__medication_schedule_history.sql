-- =====================================================================
-- 오래곁 — 약 일정 변경 이력 (Flyway V7)
-- 근거: 기획서 "약 일정 변경 이력"(.company/plans/약-일정-변경-이력.md)
--
-- 약의 시각(times)이 바뀌어도 과거 날짜의 투약 예정 횟수가 틀어지지 않도록, 시각을 날짜 범위(버전)로 남긴다.
-- V1~V6 의 설계 원칙을 따른다: user_id 필수, (medication_id, user_id) 복합 외래키, users·pets 삭제 시 함께 삭제.
-- 새 개인정보는 없다(이미 medications.times 에 있는 시각과 날짜뿐). 약 이름·용량은 복제하지 않는다.
-- =====================================================================

create table medication_schedule_versions (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users (id) on delete cascade,
  medication_id  uuid not null,
  times          time[] not null
                 check (cardinality(times) between 1 and 3
                        and array_position(times, null) is null),
  valid_from     date not null,   -- 기록 날짜(새벽 4시 규칙), 포함
  valid_to       date,            -- 기록 날짜, 포함. null = 지금도 유효
  created_at     timestamptz not null default now(),
  constraint msv_medication_fk foreign key (medication_id, user_id)
    references medications (id, user_id) on delete cascade,
  constraint msv_valid_range check (valid_to is null or valid_to >= valid_from),
  constraint msv_medication_from_key unique (medication_id, valid_from)
);

comment on table medication_schedule_versions is
  '약 시각 변경 이력. 약당 열린 버전(valid_to is null)은 최대 1개. 기록 날짜는 서버(RecordDateCalculator)가 계산해 넣는다.';

-- 약당 열린 버전 1개 (동시 변경의 최종 방어선)
create unique index msv_one_open_per_medication
  on medication_schedule_versions (medication_id) where valid_to is null;
create index msv_user_medication_from_idx
  on medication_schedule_versions (user_id, medication_id, valid_from);

-- ---------------------------------------------------------------------
-- 백필: 이력이 없던 시기의 정확한 과거 일정은 복구할 수 없다. 현재 times 를 등록일부터 쭉 적용한 것으로 본다.
--  - 활성 약: 등록 기록 날짜 ~ 열림
--  - 삭제(비활성) 약: 등록 기록 날짜 ~ (삭제 기록 날짜 - 1). 삭제 시각은 updated_at(트리거가 UPDATE 마다 now() 로 덮어씀)
--  - 등록 당일에 삭제한 약(범위가 비는 경우)은 행을 만들지 않는다
--
-- 주의: 아래 기록 날짜 환산은 RecordDateCalculator 의 CUTOFF(04:00)와 app.zone 기본값(Asia/Seoul)을 SQL 에 복제한 것이다.
--       운영 APP_ZONE 이 Asia/Seoul 이 아니거나 CUTOFF 를 바꾸면 이 백필은 어긋난다(배포 전 확인 필요).
--       일치 여부는 MedicationScheduleBackfillTest 가 검사한다(설정값·CUTOFF 와 이 파일의 상수 비교 + recordDateOf 대조).
-- ---------------------------------------------------------------------
insert into medication_schedule_versions (user_id, medication_id, times, valid_from, valid_to)
select b.user_id, b.id, b.times, b.from_date,
       case when b.active then null else b.deleted_date - 1 end
from (
  select m.user_id, m.id, m.times, m.active,
         ((m.created_at at time zone 'Asia/Seoul') - interval '4 hours')::date as from_date,
         ((m.updated_at at time zone 'Asia/Seoul') - interval '4 hours')::date as deleted_date
  from medications m
  where true /* BACKFILL_FILTER */
) b
where b.active or b.deleted_date - 1 >= b.from_date
on conflict do nothing;
