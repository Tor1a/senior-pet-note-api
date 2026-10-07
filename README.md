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
   ├─ today/     "오늘" 화면 조회 + 제안값 계산(SuggestionService)
   └─ event/     지표 이벤트
   src/main/resources/db/migration/V1__init_schema.sql   전체 스키마
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
| `PHOTO_DIR` | | `./data/photos` | 반려동물 사진 저장 폴더(`app.photo.dir`) |
| `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` | 테스트 시 | `localhost:5433/seniorpet_test` / `seniorpet` / `$POSTGRES_PASSWORD` | 자동 테스트용 DB |

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
| POST | `/api/events` | `{"name":"today_opened"\|"med_checked"\|"daily_log_saved", "props"?:{...}}` | 202 본문 없음 (그 외 이름 400) |

- `Pet` = `{id, name, species, birthYear, conditions, hasPhoto, createdAt, updatedAt}` (사진 경로는 노출하지 않음)
- `Medication` = `{id, petId, name, doseText, times:["08:00"], active}` — times 는 오름차순으로 저장·응답
- `DailyLog` = `{id, petId, recordDate, foodLevel, waterLevel, waterMl, weightKg, symptoms, symptomsNone, symptomOther, memo, updatedAt}`
- **기록 날짜(새벽 4시 규칙)**: `recorddate/RecordDateCalculator` 한 곳에서만 계산한다. Asia/Seoul 기준 00:00~03:59 는 전날, 04:00 부터 당일.
  `java.time.Clock` 을 주입받으므로(`common/ClockConfig`) 테스트에서 시각을 고정한다(`MutableClock`). 기준을 바꾸려면 `CUTOFF` 상수만 고친다.
- 투약 체크의 `recordDate`·`takenAt` 은 서버가 정한다(요청에 날짜 없음). 삭제(비활성)된 약으로 체크하면 404.
- **제안값**: `today/SuggestionService` — recordDate-7 ~ recordDate-1 중 값이 있는 날만 평균. 단계형·waterMl 은 정수 반올림(HALF_UP), weightKg 는 소수 둘째 자리.
- 일일 기록 검증: foodLevel/waterLevel 1~3, waterMl 0~20000, weightKg 0 초과 200 미만(소수 셋째 자리 이하는 반올림), symptoms ⊂ {vomit, diarrhea, cough, lethargy, seizure, other}(중복 제거),
  symptomsNone=true 이면 symptoms 는 비어야 함, symptomOther 는 `other` 선택 시에만·30자 이하, memo 200자 이하. 같은 날 다시 PUT 하면 전체 교체(upsert).
- 이벤트 props 는 JSON 객체만, 직렬화 1000바이트 이하. 개인정보 금지.

### 오류 응답

모든 오류는 `{"code": "...", "message": "..."}` 형식이다. `code` 로 분기하고, `message` 는 화면 표시용 한국어 문장이다(문구는 바뀔 수 있음).

| HTTP | code | 언제 |
|------|------|------|
| 400 | `VALIDATION_ERROR` | 입력값 검증 실패, JSON 형식 오류 |
| 401 | `UNAUTHORIZED` | 토큰 없음·잘못됨·만료, 로그인 실패 |
| 403 | `FORBIDDEN` | (예비) 권한 없음 |
| 400 | `INVALID_RECORD_DATE` | 일일 기록 경로의 날짜가 서버의 현재 기록 날짜와 다름(형식 오류 포함) |
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

## 데이터베이스

- 스키마: `src/main/resources/db/migration/V1__init_schema.sql` — users, pets, medications, med_logs, daily_logs, push_subscriptions, events
- 옛 Supabase 스키마에서 바뀐 점: `auth.users` → 자체 `users`(id, email unique, password_hash, created_at), `default auth.uid()`·RLS·storage 정책 제거.
  디자이너 반영 필드(`symptoms_none`, `water_level`/`water_ml`, `record_date`/`taken_at`)와 CHECK 제약, 새벽 4시 규칙 COMMENT 는 그대로다.
- **스키마 변경은 새 파일 `V2__설명.sql` 로만 한다.** 이미 적용된 V1 을 고치면 Flyway 가 체크섬 오류로 서버를 멈춘다.
- Hibernate 는 `ddl-auto: validate` (엔티티와 테이블이 맞는지 검사만 함).
- 기록 날짜 규칙: Asia/Seoul 기준 00:00~03:59 체크는 전날 `record_date`. 실제 시각은 `taken_at`. **서버가 계산한다**(`RecordDateCalculator`, 계약서 0-2).
- "오늘" 화면 API 는 V1 스키마 그대로 동작해서 V2 마이그레이션은 추가하지 않았다.
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

- 위치: `src/test/java/...` (총 46개) — `AuthApiTest`(회원가입→로그인→/api/me, 잘못된·위조 토큰 401, 중복 이메일 409, 400 검증, CORS),
  `PetOwnershipTest`(다른 사용자 pet 404, 목록 격리, 본문 userId 무시, 1마리 제한 409),
  `PetPhotoApiTest`(사진 업로드·조회·교체 시 이전 파일 삭제·삭제, 형식 오류 400, 5MB 초과 413, pet 수정),
  `TodayApiTest`(새벽 4시 경계 03:59/04:00 에서 today·med-logs·daily-logs 가 같은 날짜, 409, 일정 밖 시각 400, upsert, INVALID_RECORD_DATE, 증상 규칙, 제안값·직전 체중),
  `TodayOwnershipTest`(다른 사용자의 pet/medication/med-log/daily-log/photo 404), `EventApiTest`(허용 이름 202, 그 외 400),
  단위 테스트 `SuggestionServiceTest`(빈 데이터, 일부 항목, 1.5→2, 2.5→3, 7일 범위 밖·오늘 제외), `RecordDateCalculatorTest`
- 시각 고정: `ApiTestSupport` 가 `MutableClock` 을 `@Primary Clock` 으로 등록한다. `setSeoulTime(2026, 10, 6, 3, 59)` 처럼 쓰고, 테스트가 끝나면 실제 시각으로 돌아간다.
- 사진 테스트 파일은 `${java.io.tmpdir}/seniorpet-test-photos` 에 쓴다.
- 실제 PostgreSQL 이 필요하다: `docker compose up -d` 로 띄운 DB 안의 **`seniorpet_test`** DB 를 쓴다(개발 DB 와 분리).
  테스트는 매번 무작위 이메일로 새 사용자를 만들므로 정리 없이 반복 실행해도 된다. 비우고 싶으면 `docker compose down -v` 후 다시 `up -d`.
- 실행 명령은 위 "2-A" / "2-B" 의 `./gradlew build` (테스트만: `./gradlew test`).

## 문제 해결

- `JWT_SECRET 이 없거나 너무 짧습니다` → `.env` 를 불러왔는지(`set -a; . ./.env; set +a`), 32바이트 이상인지 확인.
- 테스트가 DB 연결 실패 → `docker compose ps` 로 DB 가 healthy 인지, Docker 빌드 시 `--network senior-pet-note_default` 를 붙였는지 확인.
- Windows Git Bash 에서 curl 로 한글 JSON 을 `-d '...'` 로 보내면 인코딩이 깨져 400 이 날 수 있다. UTF-8 파일로 저장해 `--data-binary @파일.json` 으로 보낸다(서버 문제 아님).
