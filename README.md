# 시니어펫 노트 — 백엔드 API (Java / Spring Boot)

> 웹·앱 클라이언트는 별도 저장소 `senior-pet-note-client` 에 있다. 저장소 분리 근거: 분리 전 통합 저장소의 회사 문서 `docs/decisions/2026-10-07-저장소-분리.md` (이 저장소에는 없음)

- 작성: developer(백엔드) / 2026-10-06
- 근거: `docs/decisions/2026-10-06-기술-스택-변경.md`, `docs/decisions/2026-10-06-MVP-세부-결정.md` (분리 전 통합 저장소의 회사 문서, 이 저장소에는 없음)
- 스택: Java 21, Spring Boot 3.5, Gradle 8.14(wrapper), Spring Security + JWT(jjwt, HS256), Spring Data JPA, Flyway, PostgreSQL 17

## 폴더 구성

```
senior-pet-note-api/
├─ docker-compose.yml          개발용 PostgreSQL 17 (호스트 포트 5433)
├─ .env.example                환경변수 예시 (.env 로 복사해서 사용, .env 는 커밋 금지)
├─ docker/postgres-init/       DB 최초 생성 시 테스트용 DB(seniorpet_test) 생성
├─ _archive/supabase/          옛 Supabase 스키마 (보관용, 사용 안 함)
├─ docs/                       API 명세 (클라이언트 저장소가 참고하는 기준)
├─ build.gradle, gradlew       Gradle wrapper (PC 에 Gradle 설치 불필요)
└─ src/main/java/com/oraegyeot/seniorpet/
   ├─ common/    공통 오류 형식(ApiError/ApiException/GlobalExceptionHandler), OwnedRepository, 헬스체크
   ├─ security/  JWT 발급·검증, 보안 필터, CORS, @CurrentUserId
   ├─ user/      users 엔티티·저장소
   ├─ auth/      회원가입·로그인·내 정보 API
   ├─ pet/       반려동물 API (소유자 범위 패턴의 기준 예시), pet/photo/ 사진 업로드·조회
   ├─ recorddate/ 기록 날짜(새벽 4시 규칙) 계산 — 이 한 곳에서만 계산
   ├─ medication/ 투약 일정, medlog/ 투약 체크, dailylog/ 일일 기록
   ├─ history/   지난 기록 조회(기간별 일일 기록 + 투약 집계)
   ├─ today/     "오늘" 화면 조회 + 제안값 계산(SuggestionService)
   ├─ reminder/  투약 알림 규칙 API + 1분 주기 발송 작업(ReminderDispatcher, ReminderScheduler)
   ├─ push/      기기 토큰 API + 푸시 발송기(PushSender: FCM / 로그)
   └─ event/     지표 이벤트
   src/main/resources/db/migration/V1__init_schema.sql   초기 스키마
   src/main/resources/db/migration/V2__medication_reminders_and_device_tokens.sql   투약 알림·기기 토큰
```

## 실행 방법

> 아래 명령은 이 저장소 최상위 폴더에서 macOS 터미널(zsh/bash) 또는 Windows Git Bash 로 실행한다고 가정한다.
> 이 PC 의 5432 포트는 다른 Postgres 컨테이너(local-postgresql)가 쓰고 있어서 **이 프로젝트 DB 는 5433 포트**를 쓴다.

### 0) 처음 한 번: 환경변수 파일 만들기

```bash
cp .env.example .env
# .env 를 열어 POSTGRES_PASSWORD, JWT_SECRET 을 임의의 긴 값으로 바꾼다
# JWT_SECRET 예: openssl rand -base64 48
```

### 1) DB 띄우기

```bash
docker compose up -d          # PostgreSQL 17, localhost:5433, DB: seniorpet / 테스트용: seniorpet_test
docker compose ps             # STATUS 가 healthy 인지 확인
```

DB 스키마는 서버가 시작될 때 Flyway 가 자동으로 적용한다(`db/migration/V*.sql`).

### 2-A) JDK 없이 Docker 로 빌드·테스트·실행 (Windows Git Bash 기준)

> `pwd -W`, `MSYS_NO_PATHCONV` 는 Git Bash 전용이다. macOS·Linux 에서는 `MSYS_NO_PATHCONV=1` 을 빼고 `$(pwd -W)` 를 `$(pwd)` 로 바꾼다.

```bash
set -a; . ./.env; set +a      # .env 값을 현재 셸에 불러오기

# 빌드 + 테스트 (compose 네트워크에 붙어서 postgres:5432 의 seniorpet_test DB 사용)
MSYS_NO_PATHCONV=1 docker run --rm --network senior-pet-note_default \
  -e TEST_DB_URL=jdbc:postgresql://postgres:5432/seniorpet_test \
  -e TEST_DB_PASSWORD="$POSTGRES_PASSWORD" \
  -v "$(pwd -W):/workspace" -v seniorpet-gradle-cache:/root/.gradle \
  -w /workspace eclipse-temurin:21-jdk ./gradlew build --no-daemon

# 서버 실행 (http://localhost:8080). 중지: docker stop seniorpet-api
MSYS_NO_PATHCONV=1 docker run -d --rm --name seniorpet-api --network senior-pet-note_default -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://postgres:5432/seniorpet \
  -e DB_PASSWORD="$POSTGRES_PASSWORD" -e JWT_SECRET="$JWT_SECRET" \
  -v "$(pwd -W)/build/libs:/app:ro" \
  eclipse-temurin:21-jre java -jar /app/senior-pet-backend-0.0.1-SNAPSHOT.jar

curl http://localhost:8080/api/health   # {"status":"ok"}
```

- `seniorpet-gradle-cache` 는 Gradle 의존성 캐시용 Docker 볼륨이다(두 번째 빌드부터 빨라짐). 지워도 된다: `docker volume rm seniorpet-gradle-cache`
- 첫 빌드는 의존성 다운로드 때문에 1~3분 걸린다.

### 2-B) JDK 21 을 설치한 경우 (IDE 개발 권장 방식, macOS 포함)

```bash
set -a; . ./.env; set +a
TEST_DB_PASSWORD="$POSTGRES_PASSWORD" ./gradlew build          # 테스트는 localhost:5433/seniorpet_test 사용
DB_PASSWORD="$POSTGRES_PASSWORD" ./gradlew bootRun             # 서버 실행 (localhost:8080, DB localhost:5433/seniorpet)
```

macOS 에서 기본 `java` 가 21 이 아니어도 된다. Gradle toolchain 이 설치된 JDK 21 을 찾아 쓴다(`/usr/libexec/java_home -V` 로 21 이 있는지 확인).
Docker Desktop 이 꺼져 있으면 `docker compose up -d` 전에 `open -a Docker` 로 켠다.

Windows PowerShell 이라면 `gradlew.bat` 를 쓰고 환경변수는 `$env:DB_PASSWORD = "..."` 처럼 지정한다.
IntelliJ 에서는 실행 구성의 Environment variables 에 `DB_PASSWORD`, `JWT_SECRET` 을 넣는다.

## 환경변수

| 이름 | 필수 | 기본값 | 설명 |
|------|------|--------|------|
| `JWT_SECRET` | **필수** | 없음 | JWT 서명 키. 32바이트 이상. 없거나 짧으면 서버가 시작되지 않는다 |
| `DB_URL` | | `jdbc:postgresql://localhost:5433/seniorpet` | DB 주소 |
| `DB_USERNAME` | | `seniorpet` | DB 사용자 |
| `DB_PASSWORD` | 사실상 필수 | 빈 값 | DB 비밀번호 (`.env` 의 `POSTGRES_PASSWORD` 와 같은 값) |
| `JWT_EXPIRATION` | | `7d` | 토큰 유효기간 (예: `7d`, `12h`) |
| `CORS_ALLOWED_ORIGINS` | | `http://localhost:5173,http://localhost:4173` | 허용할 웹 주소(쉼표 구분). 5173 = vite dev, 4173 = vite preview |
| `PORT` | | `8080` | 서버 포트 |
| `APP_ZONE` | | `Asia/Seoul` | 서비스 기준 시간대(`app.zone`). 기록 날짜 새벽 4시 규칙·알림 발송 시각·지난 기록 기간 계산에 쓴다. IANA 이름(`UTC`, `Asia/Seoul`, `America/New_York`). 틀린 값이면 서버가 시작되지 않는다. JVM·DB 시간대와는 별개(아래 "시간대") |
| `PHOTO_DIR` | | `./data/photos` | 반려동물 사진 저장 폴더(`app.photo.dir`) |
| `FCM_ENABLED` | | `false` | `true` 면 실제 FCM 으로 투약 알림 발송. `false` 면 로그 발송기(실제 발송 없이 로그만) |
| `FCM_CREDENTIALS_BASE64` | `FCM_ENABLED=true` 일 때 필수 | 빈 값 | Firebase 서비스 계정 JSON 을 base64 로 인코딩한 값(예: `base64 -i key.json`). **환경변수로만** 넣는다(키 파일을 저장소·서버 폴더에 두지 않음). 비었거나 해석할 수 없으면 서버가 시작되지 않는다. 프로젝트 id 는 이 JSON 에서 읽는다 |
| `REMINDER_SCHEDULER_ENABLED` | | `true` | 1분 주기 알림 발송 스케줄러 on/off. 자동 테스트는 꺼져 있다 |
| `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` | 테스트 시 | `localhost:5433/seniorpet_test` / `seniorpet` / `$POSTGRES_PASSWORD` | 자동 테스트용 DB |

- 늦게라도 보내는 최대 지연은 `application.yml` 의 `app.reminder.catch-up`(기본 `PT10M`, 환경변수 없음).
- Firebase 프로젝트 생성·서비스 계정 키 발급·운영 환경변수 등록은 **대표님 작업**(외부 서비스 연결, 승인 필요). 그 전까지는 `FCM_ENABLED=false` 로 개발·테스트한다.

`docker-compose.yml` 은 `.env` 의 `POSTGRES_PASSWORD` 를 읽는다. `.env` 는 비밀값이므로 커밋·공유 금지(`.gitignore` 에 등록됨). 커밋 대상은 `.env.example` 뿐이다.

## API

- 기본 주소: `http://localhost:8080`
- 요청·응답 모두 JSON (`Content-Type: application/json`, UTF-8)
- **JSON 필드 표기는 camelCase** 다 (예: `accessToken`, `birthYear`, `createdAt`, 앞으로 `symptomsNone`, `waterLevel`, `recordDate`, `takenAt`).
  DB 컬럼은 snake_case(`symptoms_none`)지만 API 는 항상 camelCase 로 변환해서 주고받는다.
- 시각(`createdAt` 등)은 ISO-8601 UTC 문자열(예: `2026-10-06T07:28:47.490621Z`). 날짜(`recordDate`)는 `YYYY-MM-DD`.
- 인증이 필요한 API 는 `Authorization: Bearer <accessToken>` 헤더를 보낸다.

### 인증 (웹·앱과 합의한 계약 — 바꾸지 말 것)

| 메서드 | 경로 | 인증 | 요청 | 성공 응답 |
|--------|------|------|------|-----------|
| GET | `/api/health` | 불필요 | - | 200 `{"status":"ok"}` |
| POST | `/api/auth/signup` | 불필요 | `{"email","password"}` (비밀번호 8자 이상) | 201 `{"accessToken","user":{"id","email"}}` |
| POST | `/api/auth/login` | 불필요 | `{"email","password"}` | 200 `{"accessToken","user":{"id","email"}}` |
| GET | `/api/me` | 필요 | - | 200 `{"id","email"}` |

- JWT HS256, 만료 7일, refresh 토큰 없음(만료되면 다시 로그인). 토큰 subject = 사용자 id.
- 이메일은 앞뒤 공백 제거 + 소문자로 저장·비교한다(`A@B.com` 으로 가입 → `a@b.com` 으로 로그인 가능, 응답의 email 은 소문자).
- 비밀번호는 BCrypt 해시로만 저장한다. 최대 길이 72자(BCrypt 한계, 초과 시 400).
- 로그인 실패는 "이메일 없음"과 "비밀번호 틀림"을 구분하지 않고 둘 다 401 `UNAUTHORIZED`.

### "오늘" 기록 화면 API — 계약서: `docs/api-today.md` (웹과 합의, 바꾸려면 비서실장에게 먼저 알림)

모든 API 는 로그인 필요. 남의 리소스·없는 리소스는 404 `NOT_FOUND`.

| 메서드 | 경로 | 요청 | 성공 응답 |
|--------|------|------|-----------|
| GET | `/api/pets` | - | 200 `[Pet]` (본인 것만, MVP 0~1개) |
| POST | `/api/pets` | `{"name"(1~30자), "species"("dog"\|"cat"), "birthYear"?(1980~2100), "conditions"?(200자)}` | 201 `Pet` (2마리째 409 `PET_LIMIT_REACHED`) |
| GET | `/api/pets/{id}` | - | 200 `Pet` |
| PUT | `/api/pets/{id}` | POST 와 같음 | 200 `Pet` |
| PUT | `/api/pets/{id}/photo` | multipart, 필드명 `file`, jpeg/png/webp, 5MB 이하 | 200 `Pet` (형식 오류 400 `INVALID_FILE`, 초과 413 `FILE_TOO_LARGE`) |
| GET | `/api/pets/{id}/photo` | - | 200 이미지 바이너리(Content-Type 포함). 사진 없으면 404 |
| DELETE | `/api/pets/{id}/photo` | - | 204 (사진이 없어도 204) |
| GET | `/api/pets/{petId}/medications` | - | 200 `[Medication]` (활성 약만, 등록 순) |
| POST | `/api/pets/{petId}/medications` | `{"name"(1~50자), "doseText"?(50자), "times":["08:00","20:00"](1~3개, HH:mm, 중복 불가)}` | 201 `Medication` |
| PUT | `/api/medications/{id}` | POST 와 같음 | 200 `Medication` |
| DELETE | `/api/medications/{id}` | - | 204 (비활성화. 과거 투약 기록은 남음) |
| GET | `/api/pets/{petId}/today` | - | 200 `{recordDate, cutoffNotice, doses[], dailyLog, suggestions, lastWeight}` |
| POST | `/api/med-logs` | `{"medicationId","scheduledTime":"08:00"}` | 201 `{id, medicationId, recordDate, scheduledTime, takenAt}` (일정에 없는 시각 400, 중복 409 `ALREADY_CHECKED`) |
| DELETE | `/api/med-logs/{id}` | - | 204 (체크 취소) |
| PUT | `/api/pets/{petId}/daily-logs/{recordDate}` | `{foodLevel, waterLevel, waterMl, weightKg, symptoms, symptomsNone, symptomOther, memo}` | 200 `DailyLog` (날짜가 현재 기록 날짜와 다르면 400 `INVALID_RECORD_DATE`) |
| GET | `/api/pets/{petId}/daily-logs?from&to` | 생략 가능(기본 30일, 최대 90일) | 200 `{petId, from, to, recordDate, medicationBasis, days[{recordDate, dailyLog\|null, medication{scheduledCount, takenCount}}]}` (기간 오류 400 `INVALID_DATE_RANGE`). 계약서: `docs/api-history.md` |
| POST | `/api/events` | `{"name":"today_opened"\|"med_checked"\|"daily_log_saved", "props"?:{...}}` | 202 본문 없음 (그 외 이름 400) |

- `Pet` = `{id, name, species, birthYear, conditions, hasPhoto, createdAt, updatedAt}` (사진 경로는 노출하지 않음)
- `Medication` = `{id, petId, name, doseText, times:["08:00"], active}` — times 는 오름차순으로 저장·응답
- `DailyLog` = `{id, petId, recordDate, foodLevel, waterLevel, waterMl, weightKg, symptoms, symptomsNone, symptomOther, memo, updatedAt}`
- **기록 날짜(새벽 4시 규칙)**: `recorddate/RecordDateCalculator` 한 곳에서만 계산한다. 서비스 시간대(`app.zone`, 기본 Asia/Seoul) 기준 00:00~03:59 는 전날, 04:00 부터 당일.
  `java.time.Clock` 을 주입받으므로(`common/ClockConfig`) 테스트에서 시각을 고정한다(`MutableClock`). 기준을 바꾸려면 `CUTOFF` 상수만 고친다.
- 투약 체크의 `recordDate`·`takenAt` 은 서버가 정한다(요청에 날짜 없음). 삭제(비활성)된 약으로 체크하면 404.
- **제안값**: `today/SuggestionService` — recordDate-7 ~ recordDate-1 중 값이 있는 날만 평균. 단계형·waterMl 은 정수 반올림(HALF_UP), weightKg 는 소수 둘째 자리.
- 일일 기록 검증: foodLevel/waterLevel 1~3, waterMl 0~20000, weightKg 0 초과 200 미만(소수 셋째 자리 이하는 반올림), symptoms ⊂ {vomit, diarrhea, cough, lethargy, seizure, other}(중복 제거),
  symptomsNone=true 이면 symptoms 는 비어야 함, symptomOther 는 `other` 선택 시에만·30자 이하, memo 200자 이하. 같은 날 다시 PUT 하면 전체 교체(upsert).
- 이벤트 props 는 JSON 객체만, 직렬화 1000바이트 이하. 개인정보 금지.

### 투약 알림·기기 토큰 API — 계약서: `docs/api-reminders.md` (클라이언트와 합의 대상)

모든 API 로그인 필요. 남의 리소스·없는 리소스는 404 `NOT_FOUND`. 새 오류 코드는 없다.

| 메서드 | 경로 | 요청 | 성공 응답 |
|--------|------|------|-----------|
| GET | `/api/medications/{id}/reminder` | - | 200 `Reminder` (설정 전이면 기본값 `enabled:false, repeat:"daily"`, `startDate`=현재 기록 날짜, `updatedAt:null`). 비활성·남의 약 404 |
| PUT | `/api/medications/{id}/reminder` | `{"enabled", "repeat":"daily"\|"weekly"\|"interval", "daysOfWeek"?:["mon","wed"], "intervalDays"?(2~30), "startDate"?:"YYYY-MM-DD", "endDate"?:"YYYY-MM-DD"}` | 200 `Reminder` (upsert, 전체 교체) |
| PUT | `/api/devices` | `{"token"(1~4096자, 공백 불가), "platform":"android"\|"ios"\|"web"}` | 200 `Device` (같은 토큰 재등록 = last_seen_at 갱신, 다른 사용자 토큰이면 현재 사용자로 이전) |
| DELETE | `/api/devices/{id}` | - | 204 (남의 것·없는 것 404) |

- `Reminder` = `{medicationId, enabled, repeat, daysOfWeek:["mon"], intervalDays, startDate, endDate, times:["08:00"], nextFireAt, updatedAt}`
  - `times` 는 약의 투약 시각(읽기 전용, 수정은 기존 `PUT /api/medications/{id}`). 알림 전용 시각은 없다.
  - `nextFireAt` = 지금 이후 첫 발송 예정 시각(ISO-8601 UTC). `enabled:false` 이거나 종료일이 지났으면 `null`. 최대 400일 앞까지만 계산
- `Device` = `{id, platform, createdAt, lastSeenAt}` (토큰 값은 응답에 다시 내보내지 않음)
- 검증(모두 400 `VALIDATION_ERROR`): `repeat`·`enabled` 필수. `weekly` 는 `daysOfWeek` 1~7개(mon~sun, 중복 불가, 응답은 요일 순 정렬), 다른 repeat 에서 값이 든 `daysOfWeek` 를 보내면 400(빈 배열·생략은 허용).
  `interval` 은 `intervalDays` 필수(2~30), 다른 repeat 에서 보내면 400. `startDate` 생략 시 현재 기록 날짜(과거 허용), `endDate` < `startDate` 면 400.
- 기기 토큰은 사용자당 최대 10개. 11번째 등록 시 `last_seen_at` 이 가장 오래된 토큰을 지우고 등록(오류 아님).
- **반복 규칙 판정 기준은 기록 날짜**(새벽 4시 규칙). 회차 (기록 날짜 D, 시각 t) 의 발송 시각은 t ≥ 04:00 이면 D 의 t, t < 04:00 이면 D+1 의 t(서울).
  계산은 `RecordDateCalculator.slotInstant` 한 곳에서만 한다. 반복 규칙은 알림에만 적용되고 "오늘" 화면은 그대로다(`docs/api-today.md` 변경 없음).
- 클라이언트는 로그아웃 직전에 `DELETE /api/devices/{id}` 를 호출해야 한다(서버에 로그아웃 API 없음).

### 투약 알림 발송 (구현됨: `reminder/ReminderDispatcher`, `push/`)

- `ReminderScheduler` 가 매 분 0초(서울) `ReminderDispatcher.dispatchDue()` 를 부른다. 스케줄러 스레드는 1개(순차 처리).
- 지금 기준 **(now − 10분, now]** 안에 발송 시각이 있는 회차만 보낸다(서버 재시작·지연 시 10분까지 늦게라도 보냄). 알림을 켜거나 약을 수정한 시각보다 이전 회차는 보내지 않는다(소급 발송 방지).
- 중복 방지: `reminder_dispatches` 에 `insert ... on conflict (reminder_dispatches_once_per_slot) do nothing` 으로 회차를 **선점**한 경우에만 보낸다. 여러 인스턴스가 동시에 돌아도 회차당 최대 1회(분산 락 불필요).
- 최대 1회(at-most-once): 선점을 커밋한 뒤 FCM 을 호출한다. 호출 중 서버가 죽으면 그 회차는 `claimed` 로 남고 재발송하지 않는다. 일시 오류도 재시도하지 않는다(`failed`).
- 이미 투약 체크한 회차는 `skipped_taken`, 기기가 없으면 `no_device` 로 기록만 남긴다. FCM 호출은 DB 트랜잭션 밖에서 한다.
- FCM 이 `UNREGISTERED`·`SENDER_ID_MISMATCH` 를 돌려주면 그 토큰을 `device_tokens` 에서 지운다. `INVALID_ARGUMENT`(메시지 문제일 수도 있음)를 포함한 그 밖의 오류는 토큰 유지(`INVALID_ARGUMENT` 는 ERROR 로그).
- 소급 방지 기준(규칙·약 수정 시각)은 UPDATE 트리거의 DB `now()` 다. 앱 서버와 DB 시계가 어긋나면 경계 회차 판정이 달라질 수 있다.
- `app.reminder.catch-up` 은 24시간 미만이어야 한다(넘으면 서버 시작 실패). 푸시 본문은 반려동물 10자·약 20자·용량 10자로 말줄임.
- 발송기: `FCM_ENABLED=true` → `FcmPushSender`(firebase-admin, `sendEachForMulticast`), 기본값 → `LoggingPushSender`(토큰 앞 8자만 로그). 테스트 → `FakePushSender`.
- 푸시 문구: 제목 "투약 시간이에요", 본문 "{반려동물} · {약} {용량}", `data` = `type, medicationId, petId, recordDate, scheduledTime` (상세: `docs/api-reminders.md` 4절).

### 오류 응답

모든 오류는 `{"code": "...", "message": "..."}` 형식이다. `code` 로 분기하고, `message` 는 화면 표시용 한국어 문장이다(문구는 바뀔 수 있음).

| HTTP | code | 언제 |
|------|------|------|
| 400 | `VALIDATION_ERROR` | 입력값 검증 실패, JSON 형식 오류 |
| 401 | `UNAUTHORIZED` | 토큰 없음·잘못됨·만료, 로그인 실패 |
| 403 | `FORBIDDEN` | (예비) 권한 없음 |
| 400 | `INVALID_RECORD_DATE` | 일일 기록 경로의 날짜가 서버의 현재 기록 날짜와 다름(형식 오류 포함) |
| 400 | `INVALID_DATE_RANGE` | 지난 기록 조회의 from/to 형식 오류, from>to, 91일 이상, 미래 to |
| 400 | `INVALID_FILE` | 사진 형식 오류(Content-Type·매직 바이트 불일치, 빈 파일, file 필드 없음) |
| 404 | `NOT_FOUND` | 없는 리소스 **또는 남의 리소스** |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 메서드 |
| 409 | `EMAIL_TAKEN` | 이미 가입된 이메일 |
| 409 | `PET_LIMIT_REACHED` | 반려동물 2마리째 등록 시도 |
| 409 | `ALREADY_CHECKED` | 이미 체크한 투약 회차 |
| 413 | `FILE_TOO_LARGE` | 사진 5MB 초과 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

### CORS

- 허용 출처: `CORS_ALLOWED_ORIGINS` (기본 `http://localhost:5173`, `http://localhost:4173`). `/api/**` 에 적용.
- 허용 메서드: GET, POST, PUT, PATCH, DELETE, OPTIONS / 허용 헤더: `Authorization`, `Content-Type`
- preflight(OPTIONS)는 인증 없이 통과한다(보안 필터보다 먼저 CORS 처리). 쿠키는 쓰지 않는다(allowCredentials=false).
- 허용되지 않은 출처의 preflight 는 403.

## 보안 규칙: "본인 데이터만" (RLS 대체)

Supabase RLS 가 없어졌으므로 **API 계층이 유일한 방어선**이다. 새 리소스 API 를 만들 때 아래 규칙을 반드시 지킨다.

1. **사용자 id 는 `@CurrentUserId UUID userId` 로만 얻는다.** 요청 본문·쿼리·경로의 `userId` 는 절대 쓰지 않는다(요청 DTO 에 userId 필드를 만들지 않는다).
2. **저장소는 `OwnedRepository<T, ID>` 를 상속한다.** `JpaRepository` 를 상속하지 않는다.
   `OwnedRepository` 에는 `findById`/`findAll`/`deleteById` 같은 "user_id 조건 없는" 메서드가 없다.
   조회 메서드는 이름에 `ByUserId` / `AndUserId` 가 들어가야 한다. `@Query` 를 직접 쓸 때도 `where user_id = :userId` 를 반드시 넣는다.
3. **서비스 메서드의 첫 파라미터는 `UUID userId`.** 단건 접근은 `getOwned(userId, id)` 패턴으로 하고, 못 찾으면 `ApiException.notFound()` (404).
   남의 것이어도 403 이 아니라 **404** 로 응답한다(존재 여부 비노출).
4. **부모를 참조하는 요청은 부모 소유부터 검증한다.** 예: 투약 등록 시 `petService.getOwned(userId, petId)` 를 먼저 호출한다.
   DB 도 (pet_id, user_id) 복합 외래키로 한 번 더 막는다.
5. **생성 시 엔티티의 userId 는 서비스 파라미터 userId 로 채운다.** 엔티티의 userId 는 setter 가 없고 `updatable = false`.
6. **새 공개(비로그인) API 는 `SecurityConfig` 에 명시적으로 추가해야만 열린다.** 기본값은 "로그인 필요".
7. **테스트 필수:** 리소스마다 "다른 사용자의 id 로 접근하면 404, 목록에 안 보임" 테스트를 넣는다 (`PetOwnershipTest` 참고).
8. 탈퇴(users 삭제)한 사용자의 토큰은 보안 필터에서 거부된다. 데이터는 `on delete cascade` 로 함께 삭제된다.
9. **예외: 시스템 작업(투약 알림 발송).** 발송은 사용자 요청이 아니라 전 사용자 대상 작업이라 1~3번을 그대로 적용할 수 없다.
   - user_id 조건 없는 쿼리는 `reminder/ReminderDispatchQueries` 한 곳에만 둔다. 이 빈은 **`ReminderDispatcher` 에서만 주입한다**(컨트롤러·사용자 서비스 주입 금지).
     발송기가 사용자 데이터를 더 다룰 때는 userId 를 받는 기존 메서드(`MedLogRepository.exists...ByUserId...`, `DeviceTokenService.tokensOf/removeInvalid(userId, ...)`)를 쓴다.
   - 기기 토큰 이전(다른 사용자에게 등록된 같은 토큰 삭제)도 이 예외다. `DeviceTokenRepository.deleteByTokenAndUserIdNot` 은 `DeviceTokenService.register` 에서만 부르고, 응답에 다른 사용자 존재 여부를 드러내지 않는다(항상 200).
   - 확인 방법: `grep -rnE "ReminderDispatchQueries [a-z]" src/main` (필드·생성자 파라미터 선언) 결과가 `ReminderDispatcher` 뿐이어야 한다.

## 데이터베이스

- 스키마: `src/main/resources/db/migration/V1__init_schema.sql` — users, pets, medications, med_logs, daily_logs, push_subscriptions, events
- `V2__medication_reminders_and_device_tokens.sql` — 투약 알림
  - `medication_reminders`: 약별 알림 규칙(약 1개당 1행, `unique (medication_id)`). `repeat_type`·`days_of_week smallint[]`(ISO 1=월…7=일)·`interval_days` 조합을 CHECK 로 강제, `end_date >= start_date`
  - `device_tokens`: FCM 토큰(`token` 전역 unique, platform android|ios|web, `last_seen_at`)
  - `reminder_dispatches`: 발송 기록(중복 방지 겸 감사 로그). `unique (user_id, medication_id, record_date, scheduled_time)` = `med_logs_once_per_slot` 과 같은 키. 보관 기간 무기한(MVP)
  - V1 의 `push_subscriptions`(웹 푸시 VAPID용)는 쓰지 않지만 그대로 둔다(후속 마이그레이션에서 삭제 검토)
- 옛 Supabase 스키마에서 바뀐 점: `auth.users` → 자체 `users`(id, email unique, password_hash, created_at), `default auth.uid()`·RLS·storage 정책 제거.
  디자이너 반영 필드(`symptoms_none`, `water_level`/`water_ml`, `record_date`/`taken_at`)와 CHECK 제약, 새벽 4시 규칙 COMMENT 는 그대로다.
- **스키마 변경은 새 파일 `V2__설명.sql` 로만 한다.** 이미 적용된 V1 을 고치면 Flyway 가 체크섬 오류로 서버를 멈춘다.
- Hibernate 는 `ddl-auto: validate` (엔티티와 테이블이 맞는지 검사만 함).
- 기록 날짜 규칙: 서비스 시간대(`app.zone`, 기본 Asia/Seoul) 기준 00:00~03:59 체크는 전날 `record_date`. 실제 시각은 `taken_at`. **서버가 계산한다**(`RecordDateCalculator`, 계약서 0-2).
- "오늘" 화면 API 는 V1 스키마 그대로 동작한다(V2 는 투약 알림 테이블만 추가, 기존 테이블 변경 없음).
- DB 직접 접속: `docker exec -it senior-pet-note-postgres-1 psql -U seniorpet -d seniorpet`

## 사진 파일 저장 (구현됨: `pet/photo/`)

- DB 에는 파일 자체가 아니라 **경로만** `pets.photo_path` 에 저장한다. 경로 규칙: `<user_id>/<무작위 32자리 hex>.<jpg|png|webp>`.
- **1단계(MVP): 서버 로컬 디스크.** 설정값 `app.photo.dir`(환경변수 `PHOTO_DIR`, 기본 `./data/photos` = 실행 위치 기준) 아래에 저장. `data/` 는 `.gitignore` 에 등록됨.
  업로드·조회 모두 로그인 API 를 거쳐 본인 것만 접근(정적 파일로 공개하지 않음).
- 검증: 5MB 이하(Spring multipart 한도 `max-file-size: 5MB` 도 같음 → 413), Content-Type(image/jpeg·png·webp)과 파일 앞부분 매직 바이트가 둘 다 맞아야 한다.
- 교체 시 DB 반영 후 이전 파일을 지운다. DB 반영이 실패하면 새 파일을 지운다.
- 경로 조작 방지: 파일명은 서버가 만들고(업로드 파일명 미사용), 읽기·삭제 전에 경로 형식 정규식 + 저장 폴더 안인지 확인한다.
- Docker 로 서버를 띄울 때 사진을 보존하려면 `-v <호스트폴더>:/data/photos` 처럼 볼륨을 붙인다(안 붙이면 컨테이너 삭제 시 사라짐).
- **2단계(운영): 오브젝트 스토리지**(S3 호환, 예: Cloudflare R2·AWS S3·NCP Object Storage). 저장 로직을 `PhotoStorage` 인터페이스로 감싸 구현만 교체한다.
  경로 규칙이 같으므로 파일 복사만 하면 DB 변경 없이 옮길 수 있다. 비용이 생길 수 있으니 **대표 승인 후** 진행.
- 응답에는 경로를 노출하지 않고(`hasPhoto` 만), 이미지 조회 API 또는 임시 서명 URL 을 따로 둔다.

## 테스트

- 위치: `src/test/java/...` (총 183개) — `AuthApiTest`(회원가입→로그인→/api/me, 잘못된·위조 토큰 401, 중복 이메일 409, 400 검증, CORS),
  `PetOwnershipTest`(다른 사용자 pet 404, 목록 격리, 본문 userId 무시, 1마리 제한 409),
  `PetPhotoApiTest`(사진 업로드·조회·교체 시 이전 파일 삭제·삭제, 형식 오류 400, 5MB 초과 413, pet 수정),
  `TodayApiTest`(새벽 4시 경계 03:59/04:00 에서 today·med-logs·daily-logs 가 같은 날짜, 409, 일정 밖 시각 400, upsert, INVALID_RECORD_DATE, 증상 규칙, 제안값·직전 체중),
  `HistoryApiTest`(기본 30일·오름차순, 04시 경계, 1/30/90일 200·91일 400, 잘못된 파라미터, 기록 없는 날 null, 투약 집계), `HistoryOwnershipTest`(남의 pet 404, 데이터 격리),
  `TodayOwnershipTest`(다른 사용자의 pet/medication/med-log/daily-log/photo 404), `EventApiTest`(허용 이름 202, 그 외 400),
  단위 테스트 `SuggestionServiceTest`(빈 데이터, 일부 항목, 1.5→2, 2.5→3, 7일 범위 밖·오늘 제외), `RecordDateCalculatorTest`(새벽 4시 규칙 + 알림 발송 시각 `slotInstant`·분 단위 회차)
- 투약 알림: `ReminderApiTest`(설정 전 기본값, weekly 정렬·nextFireAt, 전체 교체 행 1개, 검증 400, 비활성 약 404, 약 시각 변경 반영),
  `DeviceApiTest`(등록, 재등록 시 행 1개·lastSeenAt 갱신, 다른 사용자 토큰 이전, 11번째 등록 시 가장 오래된 것 삭제, 검증 400, 삭제),
  `ReminderOwnershipTest`(다른 사용자의 약 알림·기기 404, 본문 userId 무시), `ReminderSchemaTest`(V2 CHECK·유니크 제약),
  `ReminderDispatchTest`(정시 발송·문구·data, 중복 호출·동시 호출 1건, skipped_taken, no_device, 02:00 회차, 규칙 불일치·꺼짐·비활성·시작 전·종료 후, 지연 9분 발송·10분/11분 버림, 소급 방지, 무효 토큰 삭제, 전체 실패, 예외 격리),
  단위 테스트 `ReminderRuleTest`(daily/weekly/interval, 시작·종료일, 02:00 회차 요일, nextFireAt), `FcmPushSenderTest`(오류 코드 매핑, 자격증명 오류, 가짜 키로 초기화), `PushSenderConfigTest`(FCM_ENABLED 에 따른 발송기 선택·시작 실패)
- 푸시: `ApiTestSupport` 가 `FakePushSender` 를 `@Primary PushSender` 로 등록한다(실제 FCM 호출 0회). 테스트 프로필은 스케줄러를 끄고(`app.reminder.scheduler-enabled: false`) `dispatchDue()` 를 직접 부른다.
  발송 작업은 전 사용자의 규칙을 훑으므로 알림 테스트는 `newDisposableUserToken()` 으로 만든 사용자를 테스트 끝에 삭제한다. 단언은 자기 기기 토큰·medicationId 로 거른다.
  FCM 테스트의 서비스 계정은 실행마다 새로 만드는 가짜 RSA 키다(실제 키·네트워크 없음).
- 시각 고정: `ApiTestSupport` 가 `MutableClock` 을 `@Primary Clock` 으로 등록한다. `setSeoulTime(2026, 10, 6, 3, 59)`(기본 `app.zone`=Asia/Seoul 기준)처럼 쓰고, 테스트가 끝나면 실제 시각으로 돌아간다.
- 사진 테스트 파일은 `${java.io.tmpdir}/seniorpet-test-photos` 에 쓴다.
- 실제 PostgreSQL 이 필요하다: `docker compose up -d` 로 띄운 DB 안의 **`seniorpet_test`** DB 를 쓴다(개발 DB 와 분리).
  테스트는 매번 무작위 이메일로 새 사용자를 만들므로 정리 없이 반복 실행해도 된다. 비우고 싶으면 `docker compose down -v` 후 다시 `up -d`.
- 실행 명령은 위 "2-A" / "2-B" 의 `./gradlew build` (테스트만: `./gradlew test`).

## 시간대

### 해결됨(2026-10-08): `med_logs.scheduled_time` 이 JVM 시간대에 따라 어긋나게 저장되던 문제
- 원인: `hibernate.jdbc.time_zone: UTC` 때문에 Hibernate 가 스칼라 `LocalTime`(`MedLog.scheduledTime`)을 JVM 시간대 ↔ UTC 로 변환했다. JVM 이 Asia/Seoul 이면 16:25 가 07:25 로 저장됐다(`time[]` 배열과 `JdbcClient` 경로는 변환 없음).
- 결정(대표님 지시: "시간대는 UTC 기준으로 맞추고 zone 으로 설정"):
  - **JVM 시간대는 항상 UTC**: `SeniorPetApplication.main` 이 `TimeZone.setDefault(UTC)`, Gradle `test`/`bootRun` 은 `-Duser.timezone=UTC`. 그래서 `LocalTime` 이 JVM·서버 설정과 무관하게 입력 그대로 저장된다. `hibernate.jdbc.time_zone` 설정은 제거했다(시각 컬럼은 `timestamptz`/`time`/`time[]`/`date` 뿐이라 이득이 없고, 남기면 JVM 시간대가 달라질 때 같은 문제가 되살아난다). `spring.jackson.time-zone: UTC` 는 유지(JVM 도 UTC 라 일관).
  - **서비스 기준 시간대는 `app.zone`**(환경변수 `APP_ZONE`, 기본 `Asia/Seoul`): 기록 날짜 새벽 4시 규칙(`RecordDateCalculator`, 컷오프 04:00 은 상수), 알림 발송 시각(`slotInstant`·`minuteSlotsBetween`), 약 시각(`medications.times`)의 현지 시각 해석, 지난 기록의 기본 기간이 모두 이 값을 쓴다. 잘못된 값이면 기동 실패. `reminder_rules` 의 `timezone` 컬럼(V1)은 코드에서 쓰지 않는다.
  - API 의 시각은 계속 ISO-8601 UTC(`Z`), 날짜·`HH:mm` 은 `app.zone` 현지 값이다.
- 시각 컬럼 전수: `timestamptz`(created_at 등, `fire_at`, `taken_at`)는 `Instant` 로 절대시각이라 영향 없음. 스칼라 `time` 은 `med_logs.scheduled_time`(JPA, 이번 문제)과 `reminder_dispatches.scheduled_time`(`JdbcClient` 파라미터, 변환 없음) 두 곳. `medications.times`(`time[]`)는 변환 없음. `date`(`record_date`, `start_date`, `end_date`)는 `LocalDate` 라 영향 없음.
- 기존 DB 보정: 이전 KST JVM 에서 쓴 `med_logs.scheduled_time` 은 -9시간이다. Flyway 로 만들지 않고(환경마다 어긋난 정도가 다름) 1회성으로 `update med_logs set scheduled_time = scheduled_time + interval '9 hours'` 를 실행했다(개발 DB 1행, 테스트 DB 96행). **운영 DB 에 KST JVM 으로 쓴 행이 있다면 같은 보정이 필요하고, UTC JVM 으로 쓴 행은 보정하면 안 된다.**
- 회귀 테스트: `TimeZoneStorageTest`(JDBC 로 직접 조회, JVM 시간대를 UTC·Seoul·New_York 으로 바꿔도 동일), `RecordDateCalculatorZoneTest`(zone 별 컷오프·slotInstant), `HistoryZoneTest`(`app.zone=America/New_York`), `AppZoneConfigTest`(기본값·잘못된 값 기동 실패).
- 운영 시 `docker-compose.yml` 의 Postgres `TZ` 는 UTC 로 바꿨다(로그 표기만 영향. 컨테이너를 다시 만들 때 반영).

## 문제 해결

- `JWT_SECRET 이 없거나 너무 짧습니다` → `.env` 를 불러왔는지(`set -a; . ./.env; set +a`), 32바이트 이상인지 확인.
- `FCM_ENABLED=true 이지만 ... FCM_CREDENTIALS_BASE64 가 비어 있습니다` / `... 해석할 수 없습니다` → 서비스 계정 JSON 파일 전체를 base64 로 인코딩해 넣었는지 확인(`base64 -i key.json | tr -d '\n'`). FCM 없이 실행하려면 `FCM_ENABLED` 를 빼거나 `false`.
- 테스트가 DB 연결 실패 → `docker compose ps` 로 DB 가 healthy 인지, Docker 빌드 시 `--network senior-pet-note_default` 를 붙였는지 확인.
- Windows Git Bash 에서 curl 로 한글 JSON 을 `-d '...'` 로 보내면 인코딩이 깨져 400 이 날 수 있다. UTF-8 파일로 저장해 `--data-binary @파일.json` 으로 보낸다(서버 문제 아님).
