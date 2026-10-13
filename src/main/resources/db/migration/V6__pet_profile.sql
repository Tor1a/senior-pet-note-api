-- =====================================================================
-- 오래곁 — 반려동물 유형·생일·입양일 추가 (Flyway V6)
-- 근거: 기능 기획서 "초보 견주 온보딩 C-E-F" 단계 C
--
-- nullable 컬럼 세 개와 CHECK 만 추가한다. 기존 행·기존 쿼리에는 영향이 없다.
-- 미래 날짜 검사는 현재 시각에 의존하므로 DB 가 아니라 PetService 가 한다(달력 날짜, app.zone).
-- =====================================================================
alter table pets
  add column profile_type text check (profile_type in ('first_time', 'senior')),
  add column birth_date date check (birth_date >= date '1980-01-01'),
  add column adopted_on date check (adopted_on >= date '1980-01-01'),
  add constraint pets_adopted_after_birth check (birth_date is null or adopted_on is null or adopted_on >= birth_date),
  add constraint pets_birth_date_year check (birth_date is null or birth_year = extract(year from birth_date)::smallint);

comment on column pets.profile_type is '보호자가 고른 유형. null = 선택 안 함. 서비스는 이 값으로 판단하지 않고 화면 노출만 정한다.';
comment on column pets.birth_date is '정확한 생일(달력 날짜). 모르면 null, 연도만 알면 birth_year 만 쓴다.';
comment on column pets.adopted_on is '입양·데려온 날(달력 날짜). null = 입력 안 함.';
