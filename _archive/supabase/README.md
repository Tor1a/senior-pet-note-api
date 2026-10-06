# 시니어펫 노트 — Supabase 스키마

- 작성: developer / 2026-10-06 (1주 차)
- 근거: `../PLAN.md` 4장 데이터 모델, `../design/today-wireframe.md` 6장 개발자 메모

## 파일 구성

| 파일 | 내용 |
|------|------|
| `migrations/20261006000001_init_schema.sql` | 테이블 6개, 제약조건, 인덱스, updated_at 트리거 |
| `migrations/20261006000002_rls_policies.sql` | 모든 테이블 RLS 활성화 + 정책, anon 권한 회수 |
| `migrations/20261006000003_storage_pet_photos.sql` | 반려동물 사진용 비공개 버킷 `pet-photos` + 정책 (사진 기능을 빼면 적용 안 해도 됨) |
| `migrations/20261006000004_record_date_comment.sql` | 기록 날짜 규칙(새벽 4시 기준)을 `med_logs.record_date` 등의 COMMENT 로 명시 (스키마 변경 없음) |
| `tests/rls_check.sql` | "다른 계정의 데이터가 안 보이는지" 점검 스크립트 (끝에서 ROLLBACK, 데이터 안 남음) |

## 테이블 요약

모든 테이블에 `user_id`(기본값 `auth.uid()`)가 있고, 회원 탈퇴(auth.users 삭제) 시 함께 삭제된다.

| 테이블 | 용도 | 주요 컬럼 | 핵심 제약 |
|--------|------|-----------|-----------|
| `pets` | 반려동물 프로필 (M2) | name, species(`dog`/`cat`), birth_year, conditions, photo_path | **사용자당 1마리**(`pets_one_per_user` 유니크 인덱스. 여러 마리 도입 시 이 인덱스만 삭제) |
| `medications` | 투약 일정 (M3) | pet_id, name, dose_text, times(`time[]`, 1~3개), active | (pet_id, user_id) 복합 FK → 본인 반려동물에만 연결 |
| `med_logs` | 투약 체크 (M3·M6·M7) | medication_id, **record_date**(어느 날의 약), **scheduled_time**(어느 회차), **taken_at**(실제 체크 시각) | 회차당 1건 unique. 체크 취소 = 행 삭제 |
| `daily_logs` | 일일 기록 (M5·M6·M7) | record_date, weight_kg, water_level(1~3), water_ml, food_level(1~3), symptoms[], symptoms_none, symptom_other, memo | 반려동물당 하루 1행. 앱은 upsert(`onConflict: "user_id,pet_id,record_date"`) |
| `push_subscriptions` | 웹 푸시 구독 (M4) | endpoint, p256dh, auth_key, timezone(기본 Asia/Seoul) | (user_id, endpoint) unique |
| `events` | 지표 측정 (1장) | name(영문 소문자·숫자·_), props(jsonb), created_at | 사용자는 **추가만** 가능 |

### 기록 값 규칙 (디자이너 와이어프레임 반영)

- **안 적은 값은 null.** 0이나 "보통"으로 채우지 않는다. 저장 버튼을 안 누른 날은 `daily_logs` 행 자체가 없다.
- **증상 3가지 상태 구분**
  - 증상 없음(사용자가 "특이사항 없음" 선택): `symptoms_none = true`, `symptoms = '{}'`
  - 안 적음: `symptoms_none = false`, `symptoms = '{}'`
  - 증상 있음: `symptoms`에 1개 이상 (`symptoms_none`과 동시에 true 불가 — CHECK 제약)
  - 증상 코드: `vomit` 구토, `diarrhea` 설사, `cough` 기침, `lethargy` 기운 없음, `seizure` 발작, `other` 기타(내용은 `symptom_other`, 30자, `other` 선택 시에만)
- **음수량은 두 컬럼**: 선택형 `water_level`(1=조금 2=보통 3=많이), 직접 입력 `water_ml`(정수, 선택). 둘 다 nullable.
- **체중** `weight_kg`: "오늘 쟀어요"를 누른 날만 저장, 아니면 null.
- **투약 체크는 즉시 개별 저장**(med_logs 1행), 나머지는 [저장] 버튼으로 daily_logs 1행 upsert.
- **자정 넘긴 밤 약**: `record_date`(앱이 정하는 기록 날짜)와 `taken_at`(실제 시각)을 따로 저장한다. 규칙은 아래 "기록 날짜 규칙" 참고(2026-10-06 확정).
- 시간대: `medications.times`와 `record_date`는 보호자 기기 현지 기준. 푸시 발송 함수(3주 차)는 `push_subscriptions.timezone`으로 실제 시각을 계산한다.

## 기록 날짜 규칙(새벽 4시 기준, 전날 기록)

- 근거: `docs/decisions/2026-10-06-MVP-세부-결정.md` 결정 3 (대표 확정)
- **Asia/Seoul 기준 00:00~03:59 에 체크한 투약은 전날 `record_date` 로 저장한다.** 04:00 이후는 당일.
- 실제 체크 시각은 `med_logs.taken_at` 에 그대로 남긴다. 규칙이 바뀌어도 `taken_at` 으로 다시 계산할 수 있다.
- 계산은 앱이 한다: `app/src/lib/recordDate.ts` 의 `toRecordDate(takenAt)`, 기준 상수 `RECORD_DAY_CUTOFF_HOUR = 4` (`app/src/lib/constants.ts`). 경계값(00:00, 03:59, 04:00, 23:59) 단위 테스트 있음.
- 화면 안내 문구(상수 `RECORD_DATE_NOTICE`): "새벽 4시 전 투약은 전날 기록으로 저장돼요" — 투약 체크 근처·도움말·진료 리포트에 표시.
- DB 에는 `20261006000004_record_date_comment.sql` 로 컬럼 COMMENT 에 규칙을 적어 두었다(제약조건은 아님 — DB 는 날짜를 검증하지 않는다).
- 개발자 제안(대표 확인 필요): "오늘" 화면의 날짜와 `daily_logs.record_date` 도 같은 4시 기준을 쓴다. 새벽 1시에 앱을 열면 전날 기록 화면이 보인다. 투약과 일일 기록의 날짜가 어긋나지 않게 하기 위함이다.

## RLS 정책 요약

| 테이블 | 조회 | 추가 | 수정 | 삭제 |
|--------|------|------|------|------|
| pets / medications / med_logs / daily_logs / push_subscriptions | 본인만 | 본인 명의만 | 본인만 (user_id 변경 불가) | 본인만 |
| events | **불가** | 본인 명의만 | 불가 | 불가 |
| storage `pet-photos` | 경로 첫 폴더 = 본인 user_id | 같음 | 같음 | 같음 |

- 조건은 모두 `(select auth.uid()) = user_id`, 대상은 `authenticated` 역할만. 비로그인(`anon`)은 테이블 권한 자체를 회수했다.
- 자식 테이블은 `(부모 id, user_id)` 복합 외래키를 써서, 남의 반려동물·약 id를 알아도 내 기록을 붙일 수 없다.
- unique 키에 `user_id`를 포함해, 중복 오류로 남의 데이터 존재 여부가 드러나지 않게 했다.
- `service_role` 키는 RLS를 우회한다. **브라우저 코드·저장소에 절대 넣지 않는다.** (3주 차 푸시 발송 Edge Function에서만 사용, events 집계는 대시보드 SQL 편집기에서)

## 적용 방법 (대표 작업)

> Supabase 프로젝트 생성은 대표 승인 사항이다. 아래는 프로젝트를 만든 뒤의 절차다. 무료 플랜으로 충분하다.

### 방법 A — 대시보드 SQL Editor (권장, 설치 불필요)

1. Supabase 대시보드 → 해당 프로젝트 → 왼쪽 **SQL Editor** → New query
2. 아래 순서대로 파일 내용을 **하나씩** 붙여 넣고 Run
   1. `migrations/20261006000001_init_schema.sql`
   2. `migrations/20261006000002_rls_policies.sql`
   3. `migrations/20261006000003_storage_pet_photos.sql` (사진 업로드 — 2026-10-06 대표 결정으로 MVP 포함, 적용)
   4. `migrations/20261006000004_record_date_comment.sql` (기록 날짜 규칙 주석)
3. 점검: `tests/rls_check.sql` 전체를 붙여 넣고 Run → 결과에 **`RLS 점검 통과`** 가 나오면 성공. 실패하면 "RLS 점검 실패: ..." 메시지가 나온다(데이터는 롤백되어 남지 않음).
4. **Table Editor** 에서 6개 테이블 모두 "RLS enabled" 표시인지 확인.

### 방법 B — Supabase CLI (로컬 개발 환경을 쓸 경우)

```bash
# projects/senior-pet-note 에서
supabase init                      # supabase/config.toml 생성 (migrations 폴더는 그대로 사용)
supabase link --project-ref <프로젝트 ref>
supabase db push                   # migrations/ 를 순서대로 원격 DB 에 적용
```
그 다음 `tests/rls_check.sql` 은 방법 A의 3번처럼 SQL Editor 에서 실행한다.

### 주의
- 마이그레이션은 한 번만 적용한다(두 번 실행하면 "already exists" 오류). 수정이 필요하면 새 마이그레이션 파일을 추가한다.
- 대시보드 **Authentication → Providers** 에서 이메일(매직링크)/Google 로그인 설정은 별도 작업(M1)이다.

## 검증 기록

- 2026-10-06: 로컬 Docker `postgres:17` 이미지(이미 설치돼 있던 것)로 임시 컨테이너를 띄워, Supabase의 `auth`·`storage` 스키마와 `anon`/`authenticated` 역할을 흉내 낸 뒤 마이그레이션 3개 + `tests/rls_check.sql` 실행 → **모두 통과**.
- RLS를 일부러 끈 상태에서는 점검 스크립트가 실패하는 것도 확인함(점검 스크립트가 실제로 잡아낸다는 확인).
- 2026-10-06: `20261006000004_record_date_comment.sql` 을 같은 방식(임시 컨테이너, 마이그레이션 1·2 적용 후)으로 실행 → 오류 없이 적용, `med_logs.record_date` 주석 반영 확인.
- 한계: 실제 Supabase 환경(진짜 auth/storage 스키마, 기본 권한)에서는 아직 실행하지 않았다. 프로젝트 생성 후 방법 A 3번으로 최종 확인 필요.

## 다음 작업에서 다룰 것 (이번 범위 밖)

- 3주 차: 푸시 발송 Edge Function + pg_cron (service_role 사용)
- 2개월 차 여러 마리: `drop index public.pets_one_per_user;` 후 `create index pets_user_id_idx on public.pets (user_id);`
- 3개월 차 가족 공유: 정책을 "소유자 또는 공유 멤버" 기준으로 확장 필요
