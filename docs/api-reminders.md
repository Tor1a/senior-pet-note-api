# API 계약서: 투약 알림·기기 토큰 (v1)

- 작성: developer(백엔드), 2026-10-07
- 근거: 기능 기획서 "투약 알림 스케줄 API + FCM 푸시 발송"(대표님 확정 2026-10-07: 열린 질문 전체 권장 기본값)
- 대상: 클라이언트 개발자(`senior-pet-note-client`, Android·iOS·웹 모두 FCM)
- 계약을 바꾸려면 비서실장에게 먼저 알린다. `docs/api-today.md`(오늘 화면) 계약은 바뀌지 않았다.
- 공통 규칙은 기존 계약과 같다.
  - JSON 필드는 camelCase
  - 시각은 ISO-8601 UTC, 날짜는 `YYYY-MM-DD`, 시각만 쓸 때는 `HH:mm`
  - 오류 형식은 `{code, message}`. **새 오류 코드는 없다**(`VALIDATION_ERROR`, `NOT_FOUND`, `UNAUTHORIZED` 만 쓴다)
  - 모든 API에 `Authorization: Bearer` 필요
  - 남의 리소스나 없는 리소스는 404 NOT_FOUND

## 0. 핵심 원칙
1. **계산은 서버가 한다.** 반복 규칙 판정과 다음 발송 시각(`nextFireAt`)은 서버가 계산한다. 클라이언트는 표시만 한다.
2. **알림 시각 = 약의 투약 시각**(`Medication.times`, 하루 1~3개). 알림 전용 시각은 없다. 시각을 바꾸려면 기존 `PUT /api/medications/{id}` 를 쓴다.
3. **판정 기준은 기록 날짜**(새벽 4시 규칙, `docs/api-today.md` 0-2). 요일·간격·시작일·종료일 모두 기록 날짜로 본다.
   - 04:00 전 시각(00:00~03:59)의 회차는 **기록 날짜 다음 날** 그 시각에 발송한다.
   - 예: "월요일만 + 02:00" → 화요일 02:00 에 "월요일 약" 알림(`data.recordDate` = 월요일). "오늘" 화면·투약 체크와 같은 규칙이라 체크 여부 판정이 어긋나지 않는다.
4. **시간대는 서비스 시간대**(기본값 Asia/Seoul, 서버 설정 `app.zone` 으로 바꿀 수 있다). 투약 시각은 이 시간대의 현지 시각이다.
5. **회차(약 + 기록 날짜 + 시각)당 최대 1번** 보낸다. 이미 투약 체크한 회차는 보내지 않는다. 일시 오류가 나도 다시 보내지 않는다.
6. 이번 버전에서 반복 규칙은 **알림에만** 적용된다. "오늘" 화면은 weekly·interval 약도 지금처럼 매일 보여 준다.

## 1. 흐름 요약
1. 로그인 후 FCM 등록 토큰을 받으면 `PUT /api/devices` (앱 시작·토큰 갱신 때마다 다시 호출해도 된다)
2. 약 화면에서 `GET /api/medications/{id}/reminder` → 설정 화면 → `PUT /api/medications/{id}/reminder`
3. 서버가 예정 시각에 푸시 → 클라이언트는 `data` 로 "오늘" 화면을 열고, 체크는 기존 `POST /api/med-logs`
4. **로그아웃 직전에 `DELETE /api/devices/{id}` 를 반드시 호출한다.** 서버에 로그아웃 API 가 없어서(JWT 무상태) 해제하지 않으면 로그아웃한 기기로 알림이 계속 간다.

## 2. 투약 알림 설정
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/medications/{id}/reminder | 200 Reminder. 설정한 적 없으면 기본값(아래) |
| PUT | /api/medications/{id}/reminder | 전체 교체(upsert) → 200 Reminder |

- 삭제(비활성)된 약·남의 약·없는 약은 404 NOT_FOUND.
- 약 1개당 규칙 1개. 약을 등록해도 알림은 **기본 꺼짐**이다(사용자가 PUT 으로 켠다).

PUT 요청
```json
{
  "enabled": true,
  "repeat": "weekly",
  "daysOfWeek": ["mon", "wed"],
  "intervalDays": null,
  "startDate": "2026-10-06",
  "endDate": null
}
```
| 필드 | 필수 | 규칙 |
|---|---|---|
| enabled | 필수 | boolean. false = 알림 끔(규칙은 보존) |
| repeat | 필수 | `daily`(매일) / `weekly`(요일 지정) / `interval`(N일 간격) |
| daysOfWeek | weekly 만 | 1~7개, 값 ⊂ {mon,tue,wed,thu,fri,sat,sun}, 중복 불가. 응답은 월→일 순으로 정렬. weekly 가 아닐 때 값을 넣어 보내면 400(빈 배열 `[]` 과 생략·null 은 허용) |
| intervalDays | interval 만 | 2~30. interval 이 아닐 때 보내면 400 |
| startDate | 선택 | 생략하면 서버의 현재 기록 날짜. 과거 날짜 허용(간격 기준일 맞추기용) |
| endDate | 선택 | 이 날까지 포함. startDate 보다 앞이면 400 |

- 전체 교체라 보내지 않은 선택 필드는 지워진다(예: endDate 를 빼고 PUT 하면 종료일 없음).
- 위반은 모두 400 VALIDATION_ERROR.

Reminder 응답
```json
{
  "medicationId": "uuid",
  "enabled": true,
  "repeat": "weekly",
  "daysOfWeek": ["mon", "wed"],
  "intervalDays": null,
  "startDate": "2026-10-06",
  "endDate": null,
  "times": ["08:00", "20:00"],
  "nextFireAt": "2026-10-06T23:00:00Z",
  "updatedAt": "2026-10-06T00:00:00Z"
}
```
- `times`: 약의 투약 시각(읽기 전용).
- `nextFireAt`: 지금 이후 첫 발송 예정 시각(UTC). 꺼져 있거나 종료일이 지났거나 400일 안에 없으면 null. 이미 체크한 회차 여부는 반영하지 않는다.
- `updatedAt`: 설정한 적 없으면 null.
- 설정 전 기본값: `enabled:false, repeat:"daily", daysOfWeek:[], intervalDays:null, startDate:현재 기록 날짜, endDate:null, nextFireAt:null, updatedAt:null`.

반복 규칙 해석
- `daily`: startDate~endDate 의 매일.
- `weekly`: 기록 날짜의 요일이 daysOfWeek 에 있는 날.
- `interval`: `(기록 날짜 - startDate) % intervalDays == 0` 인 날. startDate 가 1회차.
- 알림을 켜거나 약 시각을 바꾼 시각보다 **이전 회차는 보내지 않는다**(예: 08:05 에 켜면 그날 08:00 회차는 건너뜀).
- 서버가 잠시 멈췄다가 살아나면 예정 시각에서 **10분 이내**의 회차만 늦게라도 보낸다.

## 3. 기기 토큰
| 메서드 | 경로 | 설명 |
|---|---|---|
| PUT | /api/devices | 등록(upsert) `{token, platform}` → 200 Device |
| DELETE | /api/devices/{id} | 해제 → 204. 남의 것·없는 것 404 |

- `token`: FCM 등록 토큰(SDK 의 getToken 값). 1~4096자, 공백 불가.
- `platform`: `android` / `ios` / `web`.
- 같은 토큰을 다시 등록하면 같은 `id` 가 돌아오고 `lastSeenAt` 만 갱신된다.
- 같은 토큰이 다른 계정으로 등록돼 있었다면 현재 계정으로 옮긴다(이전 계정의 알림이 이 기기로 오지 않는다). 응답은 항상 200 이고 다른 계정 정보는 드러나지 않는다.
- 계정당 최대 10개. 11번째를 등록하면 가장 오래 등록(갱신)되지 않은 토큰을 지우고 등록한다(오류 아님).
- 서버가 FCM 에서 "더 이상 쓸 수 없는 토큰"(앱 삭제 등) 응답을 받으면 그 토큰을 지운다. 앱은 시작할 때마다 PUT 으로 다시 등록하면 된다.

Device 응답 (토큰 값은 다시 내려주지 않는다 — 해제용 `id` 를 앱에 저장해 둔다)
```json
{ "id": "uuid", "platform": "android", "createdAt": "2026-10-06T00:00:00Z", "lastSeenAt": "2026-10-06T00:00:00Z" }
```

## 4. 푸시 메시지
약마다(같은 시각 약이 여러 개면 약마다 따로) 한 건씩 보낸다.

| 항목 | 값 |
|---|---|
| notification.title | `투약 시간이에요` |
| notification.body | `{반려동물 이름} · {약 이름} {용량}` — 용량이 없으면 `{반려동물 이름} · {약 이름}` (예: `초코 · 아조딜 1캡슐`). 잠금화면에 표시된다 |
| data.type | `med_reminder` |
| data.medicationId | 약 id |
| data.petId | 반려동물 id |
| data.recordDate | 회차의 기록 날짜 `YYYY-MM-DD` (새벽 02:00 회차면 전날 날짜) |
| data.scheduledTime | 회차 시각 `HH:mm` |

- data 값은 모두 문자열이다.
- 클라이언트는 `data.type == "med_reminder"` 이면 `petId` 의 "오늘" 화면을 연다. 체크는 기존 `POST /api/med-logs {medicationId, scheduledTime}`.
- 유효시간 1시간: 기기가 1시간 넘게 꺼져 있으면 지난 알림은 버려진다.
- 같은 회차는 기기에서 하나로 합쳐진다(Android collapseKey, iOS apns-collapse-id = `{medicationId}:{recordDate}:{scheduledTime}`).
- Android 우선순위 high, iOS apns-priority 10·기본 알림음(`sound: default`), 웹 TTL 3600.
- 문구는 designer G1 확정본이다. 바뀌면 이 문서를 고친다.
- 말줄임(서버 적용, 앞뒤 공백 제거 후 코드 포인트 기준): 반려동물 이름 10자 / 약 이름 20자 / 용량 10자. 넘으면 앞 N-1자 + `…`(예: 11자 이름 → 앞 9자 + `…`). 본문 최대 44자.
- 웹 푸시: `notification.tag` = collapse key(`{medicationId}:{recordDate}:{scheduledTime}`), `notification.icon` = `/pwa-192x192.png`.
