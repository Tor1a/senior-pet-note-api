# API 계약서: "오늘" 기록 화면 (v1)

- 작성: 비서실장, 2026-10-06
- 대상: backend 개발자(구현), web 개발자(호출)
- 계약을 바꾸려면 비서실장에게 먼저 알린다. 두 사람이 동시에 작업한다.
- 공통 규칙은 기존 인증 계약을 따른다.
  - JSON 필드는 camelCase
  - 시각은 ISO-8601 UTC, 날짜는 `YYYY-MM-DD`, 시각만 쓸 때는 `HH:mm`
  - 오류 형식은 `{code, message}`
  - 모든 API에 `Authorization: Bearer` 필요
  - 남의 리소스나 없는 리소스는 404 NOT_FOUND

## 0. 핵심 원칙
1. **계산은 서버가 한다.** 기록 날짜(새벽 4시 규칙)와 제안값(최근 7일 평균)은 서버가 계산해서 내려준다. 웹은 받은 값을 표시만 한다.
2. **기록 날짜 규칙:** 기준은 서비스 시간대(기본값 Asia/Seoul, 서버 설정 `app.zone`)이고, 00:00~03:59는 전날, 04:00부터 당일이다. 투약 체크, 일일 기록, "오늘" 화면 날짜에 모두 같은 규칙을 쓴다.
   - 서버는 `Clock`을 주입받아 테스트에서 시각을 고정할 수 있어야 한다.
   - 새벽 4시 전에는 "오늘" 화면이 곧 전날 화면이다. 그래서 와이어프레임의 "어젯밤 약" 카드는 따로 만들지 않는다.
3. **제안값:** `recordDate`를 뺀 직전 7일(recordDate-7 ~ recordDate-1) 중 해당 항목 값이 있는 날만 평균 낸다.
   - 단계형(foodLevel, waterLevel)은 반올림(HALF_UP)한 정수다.
   - waterMl은 정수로 반올림하고, weightKg는 소수 둘째 자리까지 쓴다.
   - 값이 하나도 없으면 null이다.

## 1. 반려동물 (기존 API 확장)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/pets | 내 반려동물 목록 (MVP는 0~1개) |
| POST | /api/pets | 생성 `{name, species:"dog"|"cat", birthYear?, conditions?}` → 201 Pet |
| GET | /api/pets/{id} | 조회 |
| PUT | /api/pets/{id} | 수정 (body는 POST와 같음) → 200 Pet |
| PUT | /api/pets/{id}/photo | 사진 업로드. multipart 필드명 `file`, jpeg/png/webp, 5MB 이하 → 200 Pet. 형식 오류는 400 INVALID_FILE, 용량 초과는 413 FILE_TOO_LARGE |
| GET | /api/pets/{id}/photo | 사진 바이너리(Content-Type 포함). 사진이 없으면 404 |
| DELETE | /api/pets/{id}/photo | 사진 삭제 → 204 |

Pet은 `{id, name, species, birthYear, conditions, hasPhoto, createdAt, updatedAt}`이다.
- 사진 저장 경로는 응답에 노출하지 않는다.
- 웹은 `GET /photo`를 토큰과 함께 fetch해서 blob URL로 표시한다.

## 2. 투약 일정
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /api/pets/{petId}/medications | 활성 약 목록 |
| POST | /api/pets/{petId}/medications | `{name, doseText?, times:["08:00","20:00"]}` (1~3개, 중복 불가) → 201 Medication |
| PUT | /api/medications/{id} | 수정 → 200 Medication |
| DELETE | /api/medications/{id} | 비활성화(active=false, 과거 기록 보존) → 204 |

Medication은 `{id, petId, name, doseText, times:["08:00"], active}`이다.

## 3. "오늘" 화면 조회
`GET /api/pets/{petId}/today` → 200
```json
{
  "recordDate": "2026-10-06",
  "cutoffNotice": "새벽 4시 전 투약은 전날 기록으로 저장돼요",
  "doses": [
    { "medicationId": "uuid", "name": "아조딜", "doseText": "1캡슐", "scheduledTime": "08:00",
      "taken": true, "medLogId": "uuid", "takenAt": "2026-10-05T23:05:00Z" }
  ],
  "dailyLog": null,
  "suggestions": { "foodLevel": 2, "waterLevel": 2, "waterMl": null, "weightKg": 4.35 },
  "lastWeight": { "weightKg": 4.4, "recordDate": "2026-10-03" }
}
```
- `doses`: 활성 약의 times를 하나씩 풀어 scheduledTime 오름차순으로 정렬한 목록이다.
- `dailyLog`: 그날 저장된 기록이 있으면 DailyLog(5번 항목), 없으면 null이다.
- `lastWeight`: recordDate 이전 기록 중 가장 최근 체중이다. 없으면 null이다.

## 4. 투약 체크
| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /api/med-logs | `{medicationId, scheduledTime:"08:00"}` → 201 `{id, medicationId, recordDate, scheduledTime, takenAt}` |
| DELETE | /api/med-logs/{id} | 체크 취소 → 204 |

- recordDate와 takenAt은 **서버가 정한다**. 클라이언트가 보낸 날짜는 받지 않는다.
- 약 일정에 없는 scheduledTime을 보내면 400 VALIDATION_ERROR다.
- 이미 체크한 회차면 409 ALREADY_CHECKED다.

## 5. 일일 기록 저장 (upsert)
`PUT /api/pets/{petId}/daily-logs/{recordDate}` → 200 DailyLog
```json
{ "foodLevel": 2, "waterLevel": null, "waterMl": 350, "weightKg": null,
  "symptoms": [], "symptomsNone": true, "symptomOther": null, "memo": "" }
```
- `recordDate`는 서버가 계산한 **현재 기록 날짜와 같아야 한다**. 다르면 400 INVALID_RECORD_DATE다. MVP에서는 지난 날짜를 수정할 수 없다.
- 검증은 DB 제약과 같다.
  - foodLevel/waterLevel은 1~3 또는 null
  - waterMl은 0~20000
  - weightKg는 0 초과 200 미만
  - symptoms는 [vomit, diarrhea, cough, lethargy, seizure, other] 중에서 고른다
  - symptomsNone이 true면 symptoms는 비어 있어야 한다
  - symptomOther는 'other'를 골랐을 때만 쓸 수 있고 30자 이하다
  - memo는 200자 이하다(화면 기준)
- DailyLog는 `{id, petId, recordDate, foodLevel, waterLevel, waterMl, weightKg, symptoms, symptomsNone, symptomOther, memo, updatedAt}`다.

## 6. 지표 이벤트
`POST /api/events` `{name, props?}` → 202 (본문 없음)
- MVP 허용 이름은 `today_opened`, `med_checked`, `daily_log_saved` 세 가지다. 나머지는 400이다.
- props 예: `{"source":"push"|"direct"}`, `{"taps":4,"durationMs":9100}`
- 개인정보는 넣지 않는다.
