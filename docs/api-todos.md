# API 계약서: 내 할 일 일정 (v1)

- 작성: developer(백엔드), 2026-10-13
- 근거: 기능 기획서 `내-할-일-일정.md`(클라이언트 저장소 `.company/plans/`) 5장. 달라진 점은 6절에 모았다.
- 대상: 클라이언트 개발자(`senior-pet-note-client`, 웹·모바일)
- 계약을 바꾸려면 비서실장에게 먼저 알린다. `docs/api-today.md`·`docs/api-reminders.md` 의 API 형식은 바뀌지 않았다.
- 공통 규칙은 기존 계약과 같다.
  - JSON 필드는 camelCase
  - 시각은 ISO-8601 UTC, 날짜는 `YYYY-MM-DD`, 시각만 쓸 때는 `HH:mm`
  - 오류 형식은 `{code, message}`. **새 오류 코드는 `TODO_LIMIT_REACHED` 하나**
  - 모든 API에 `Authorization: Bearer` 필요
  - 남의 리소스나 없는 리소스는 404 NOT_FOUND

## 0. 핵심 원칙
1. **서비스는 일정·주기를 정해 주거나 추천하지 않는다.** 보호자가 입력한 값(제목·날짜·시각·메모)만 저장하고 알린다. 기본값·예시 제목·다음 일정 자동 생성은 없다.
2. **날짜 계산은 서버가 한다.** `dateStatus`(지난 일정/오늘/내일/그 이후)는 서버의 현재 **기록 날짜**(새벽 4시 규칙, `docs/api-today.md` 0-2)와 `dueDate` 를 비교해 내려준다. 클라이언트는 날짜 차이를 계산하지 않는다.
   - 00:00~03:59 에는 "오늘" = 전날 날짜다(오늘 화면과 같다).
3. **시간대는 서비스 시간대**(`app.zone`, 기본 Asia/Seoul). `dueTime` 은 보호자가 입력한 `HH:mm` 을 그대로 저장·응답한다(시간대 변환 없음).
4. **알림 시각은 서버 설정 고정값**(하루 전 20:00, 당일 09:00). 보호자가 바꾸는 필드는 없다. 입력한 `dueTime` 은 알림 본문에 보여 줄 뿐 알림 시각에 영향을 주지 않는다.
5. 메모·병원 메모(`memo`, `hospitalNote`)는 **푸시·FCM `data`·이벤트에 싣지 않는다.** 서버 로그에도 남기지 않는다.

## 1. 엔드포인트
| 메서드 | 경로 | 요청 | 성공 응답 |
|---|---|---|---|
| GET | /api/pets/{petId}/todos?status=open\|done | - | 200 `{recordDate, todos:[Todo]}` |
| POST | /api/pets/{petId}/todos | TodoInput | 201 Todo |
| PUT | /api/todos/{id} | TodoInput (전체 교체) | 200 Todo |
| PUT | /api/todos/{id}/completion | `{"completed": true\|false}` | 200 Todo |
| DELETE | /api/todos/{id} | - | 204 (실제 삭제) |

- 남의 반려동물·남의 할 일·없는 것·UUID 형식이 아닌 id 는 모두 404 `NOT_FOUND`. 목록·등록은 반려동물 소유 검증이 먼저다.
- 요청 본문의 알 수 없는 필드(`userId`, `petId` 포함)는 무시한다. 소유자는 항상 로그인 사용자다.

### GET 목록
- `status` 생략 = `open`. `open` / `done` 이외의 값(빈 값, 대소문자 다름 포함)은 400 `VALIDATION_ERROR`.
- `recordDate`: 서버의 현재 기록 날짜(`dateStatus` 계산 기준).
- 정렬
  - `open`(미완료): `dueDate` 오름차순 → `dueTime` 오름차순(시각 없는 항목은 그 날짜 안에서 뒤) → 등록 순. 개수 제한 없음(사용자당 최대 300개).
  - `done`(완료): `completedAt` 내림차순(최근 완료 먼저), **최대 100개**.

### POST / PUT 요청 (TodoInput)
```json
{
  "title": "종합 접종",
  "dueDate": "2026-10-20",
  "dueTime": "14:30",
  "memo": "수첩 챙기기",
  "hospitalNote": null,
  "remind": true
}
```
| 필드 | 필수 | 규칙 |
|---|---|---|
| title | 필수 | 앞뒤 공백 제거 후 1~40자(코드 포인트 기준, 이모지 1자). 저장값은 공백 제거본 |
| dueDate | 필수 | `YYYY-MM-DD`. 현재 기록 날짜 기준 **-400일 ~ +730일**(양 끝 포함). 과거 날짜도 허용(이미 맞힌 접종 기록용, 알림은 가지 않음) |
| dueTime | 선택 | `HH:mm`(00:00~23:59) 또는 생략/null. 빈 문자열·`9:30`·`14:30:00` 은 400 |
| memo | 선택 | 200자 이하(코드 포인트). 빈 문자열·공백만 있으면 null 로 저장 |
| hospitalNote | 선택 | 500자 이하(코드 포인트). 빈 문자열·공백만 있으면 null 로 저장. 방문 후 보호자가 적는 자유 메모 |
| remind | 선택 | boolean. 생략/null = `true` |

- PUT 은 **전체 교체**다. 보내지 않은 선택 필드는 지워진다(`remind` 는 `true` 로 돌아간다). 완료 상태(`completed`/`completedAt`)는 PUT 으로 바뀌지 않는다(`/completion` 으로만).
- 위반은 모두 400 `VALIDATION_ERROR`(날짜 형식 오류, JSON 형식 오류 포함).

### PUT /completion
- `{"completed": true}` → 완료. 이미 완료면 **최초 `completedAt` 을 유지**한다(멱등). `{"completed": false}` → 완료 취소(`completedAt: null`), 이미 미완료여도 200.
- `completed` 가 없으면 400. 완료하면 그 할 일의 알림이 중단되고, 되돌리면 **이후** 알림 시각부터 다시 간다(이미 지난 알림은 소급해서 보내지 않는다).

### DELETE
- 실제 삭제(되돌리기 없음). 발송 기록(`todo_dispatches`)도 함께 지워진다.

## 2. Todo 응답
```json
{
  "id": "uuid",
  "petId": "uuid",
  "title": "종합 접종",
  "dueDate": "2026-10-20",
  "dueTime": "14:30",
  "memo": "수첩 챙기기",
  "hospitalNote": null,
  "remind": true,
  "completed": false,
  "completedAt": null,
  "dateStatus": "future",
  "createdAt": "2026-10-13T03:02:11.781275Z",
  "updatedAt": "2026-10-13T03:02:11.781275Z"
}
```
- `dateStatus`: `"past"`(dueDate < 기록 날짜) / `"today"` / `"tomorrow"` / `"future"`. 완료 항목에도 내려준다.
- `completedAt`: ISO-8601 UTC 또는 null. `completed` 는 `completedAt != null` 과 같다.
- `dueTime`, `memo`, `hospitalNote`: 없으면 null(필드는 항상 존재).
- `updatedAt`: DB 에 실제 저장된 값(트리거 now())이다.

목록 응답
```json
{ "recordDate": "2026-10-13", "todos": [ { "...Todo": "..." } ] }
```

## 3. 한도·오류
| HTTP | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 입력 검증 실패, `status` 값 오류, JSON 형식 오류 |
| 401 | `UNAUTHORIZED` | 토큰 없음·잘못됨·만료 |
| 404 | `NOT_FOUND` | 없는 리소스 또는 남의 리소스 |
| 409 | `TODO_LIMIT_REACHED` | 사용자당 할 일 **300개**(완료 포함)에 이미 도달한 상태에서 등록 |

- 한도는 등록(POST)에만 적용된다. 수정·완료·삭제는 항상 가능하고, 삭제하면 다시 등록할 수 있다.
- 클라이언트는 `code` 로 분기한다. `message` 문구는 바뀔 수 있다.

## 4. 알림 (서버가 푸시를 보낸다)
할 일마다 **하루 전**(예정 날짜 -1일 20:00)과 **당일**(예정 날짜 09:00, `app.zone` 현지) 최대 2회. 조건: `remind = true` 이고 미완료.

| kind | notification.title | notification.body |
|---|---|---|
| day_before | `내일 일정이 있어요` | `{반려동물 이름} · {제목}` (`dueTime` 이 있으면 ` · 14:30` 추가) |
| due_day | `오늘 일정이 있어요` | 위와 같음 |

- 말줄임(서버 적용, 앞뒤 공백 제거 후 코드 포인트 기준): 반려동물 이름 10자 / 제목 20자. 넘으면 앞 N-1자 + `…`. **메모·병원 메모는 싣지 않는다.**
- `data`(값은 모두 문자열): `type` = `todo_reminder`, `todoId`, `petId`, `dueDate`(`YYYY-MM-DD`), `kind`(`day_before` | `due_day`), `dueTime`(`HH:mm`, 시각이 있을 때만 포함). **제목은 data 에 싣지 않는다.**
- 클라이언트는 `data.type == "todo_reminder"` 이면 할 일 목록을 연다. 기존 `med_reminder` 처리와 별개다.
- 같은 알림은 기기에서 하나로 합쳐진다: collapse key = `todo:{todoId}:{kind}:{dueDate}`(Android collapseKey, iOS apns-collapse-id, 웹 notification.tag).
- **웹 푸시의 [먹였어요] 액션 버튼은 투약 알림(`med_reminder`)에만 붙는다.** 할 일 알림에는 액션 버튼이 없다(본문 탭만). 유효시간·우선순위·알림음은 투약 알림과 같다(유효 1시간).
- 발송 규칙
  - 서버가 매 분 확인한다. 예정 시각이 지난 알림은 **10분 이내**일 때만 늦게라도 보낸다(`app.reminder.catch-up`). 그보다 오래되면 버린다.
  - **(할 일, kind, 예정 날짜)당 최대 1번.** 일시 오류가 나도 다시 보내지 않는다. 날짜를 고치면 새 날짜로 다시 알린다. 같은 날짜로 다시 저장하면 중복 발송은 없다.
  - **소급 발송 없음:** 할 일을 등록·수정·완료 취소한 시각보다 이전에 있던 알림 시각은 보내지 않는다(예: 6/11 21:00 에 "6/12 일정"을 등록하면 6/11 20:00 알림은 없고 6/12 09:00 알림만 간다. 6/12 09:30 에 등록하면 알림 없음). 메모만 고쳐도 수정 시각이 갱신되므로 같은 규칙이다.
  - 완료한 할 일은 보내지 않는다. 기기 토큰이 없으면 기록만 남긴다. 무효 토큰은 지운다(투약 알림과 같다).
  - 알림 시각은 서버 설정(`app.todo.day-before-time`, `app.todo.due-day-time`)이며 04:00 이후여야 한다(기록 날짜 경계와 어긋나 하루 늦게 울리는 것을 막기 위해 서버가 시작되지 않는다).

## 5. 탈퇴·삭제
- 회원 탈퇴(`docs/api-account.md`) 시 `todos`, `todo_dispatches` 도 함께 영구 삭제된다(`users` 삭제 + `on delete cascade`).
- 반려동물이 삭제되면(현재 API 에는 반려동물 삭제가 없다) 그 할 일도 함께 삭제된다(DB 외래키 cascade).
- 할 일 제목·메모·병원 메모는 건강 관련 정보일 수 있다. 처리방침·삭제 안내 문구 갱신은 대표님 승인·법무 검토 대상이다(기획서 Q4).

## 6. 기획서(5장) 초안과 달라진 점
- `status` 가 `open`/`done` 이외이거나 빈 값(`?status=`)이면 400 (기획서는 "값 오류 400" 만 명시).
- `dueTime` 이 빈 문자열이면 400 (생략/null 만 "시각 없음").
- `memo` 가 공백만 있어도 null 로 저장(기획서는 "빈 문자열은 null").
- `title` 은 공백 제거본을 저장·응답한다.
- 등록·수정이 `remind` 를 생략하면 PUT 도 `true` 로 돌아간다(전체 교체).
- 완료 취소·수정·메모 수정 모두 `updatedAt` 을 갱신하므로 소급 방지 기준 시각도 함께 늦춰진다.
- 허용 동작: 소급 방지 기준이 `updatedAt` 이라 어떤 수정이든(메모만 고쳐도) 기준 시각이 갱신되고, 그 시각 이전의 알림 시각은 보내지 않는다.
- 발송 직전(선점 뒤) 알림을 끄거나 예정 날짜를 바꿨다면 그 회차는 보내지 않는다.
- `todo_dispatches.status` 의 `skipped_done` 은 "선점 뒤 완료되었거나 삭제된 경우"에도 쓴다.
