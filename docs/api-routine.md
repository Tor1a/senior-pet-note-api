# API 계약서: 루틴 기록 (산책·배변 횟수, v1)

- 2026-10-13 신규. 기획서: `.company/plans/루틴-기록-칩.md` 6장(클라이언트 저장소). 기존 문서(`api-today.md`, `api-history.md`, `api-undo.md`)의 문장은 바꾸지 않고, 이 문서가 **추가 필드만** 기술한다.
- 서버는 횟수를 평가·권장·비교하지 않는다. 보호자가 입력한 숫자만 저장한다(상한 20 은 오입력 방지용 검증).
- 새 엔드포인트·새 오류 코드 없음. 서버 로그에 값은 남기지 않는다.

## 1. 요청: `PUT /api/pets/{petId}/daily-logs/{recordDate}` 추가 필드

| 필드 | 타입 | 규칙 |
|---|---|---|
| `walkCount` | 정수 0~20 또는 null | 산책 횟수 |
| `poopCount` | 정수 0~20 또는 null | 배변(대변) 횟수. 색·모양 등 상태는 저장하지 않는다 |

**이 두 필드만** 다음 규칙을 따른다(구버전 클라이언트 호환).

| 요청 | 동작 |
|---|---|
| 필드 **생략**(키를 보내지 않음) | 기존 저장 값 **유지**. 행이 없으면 null |
| `0`~`20` | 저장. `0` 은 "0회로 입력"이며 null(안 적음)과 다르다 |
| 명시적 `null` | 지움(안 적음) |
| 21, -1, 소수(1.5), 숫자가 아닌 값 | 400 `VALIDATION_ERROR` (실패한 요청은 기존 값을 바꾸지 않는다) |

- 기존 8개 필드(`foodLevel`, `waterLevel`, `waterMl`, `weightKg`, `symptoms`, `symptomsNone`, `symptomOther`, `memo`)는 지금처럼 **전체 교체**(생략 = null)다. 바뀌지 않았다.
- 두 필드를 따로 다룬다: 하나만 보내면 그 필드만 바뀌고 다른 하나는 유지된다.
- 정수로 표현된 소수(`2.0`)는 정수 2 로 받는다.
- 기록 날짜 규칙(새벽 4시, `INVALID_RECORD_DATE`)과 소유권(남의 pet 404)은 기존 PUT 과 같다.

예) 저장 전 값으로 되돌리는 복원 본문(10필드):
```json
{ "foodLevel": 2, "waterLevel": null, "waterMl": null, "weightKg": null,
  "symptoms": [], "symptomsNone": false, "symptomOther": null, "memo": "",
  "walkCount": 2, "poopCount": null }
```

## 2. 응답: DailyLog 추가 필드

`PUT` 응답, `GET /today` 의 `dailyLog`, `GET /daily-logs` 의 `days[].dailyLog` 에 `walkCount`, `poopCount`(정수 또는 null)가 추가된다. 필드 추가뿐이라 구버전 클라이언트는 무시해도 된다.

- DailyLog 는 `{id, petId, recordDate, foodLevel, waterLevel, waterMl, weightKg, symptoms, symptomsNone, symptomOther, memo, walkCount, poopCount, updatedAt}` 가 된다.
- 다른 값이 전부 null 이고 루틴 값만 있는 행도 `dailyLog` 로 내려간다. 지난 기록에서는 "기록한 날"에 센다.
- `suggestions`(7일 평균 제안값)에는 루틴 값이 없다. 평균·통계도 계산하지 않는다.

## 3. 삭제·탈퇴

- `DELETE /api/pets/{petId}/daily-logs/{recordDate}`(저장 되돌리기)는 그날 일일 기록 1행 전체를 지운다 = 루틴 값도 함께 삭제. 동작 변경은 없다.
- 회원 탈퇴 시 `daily_logs` 가 `users` 의 `on delete cascade` 로 지워져 루틴 값도 함께 삭제된다.

## 4. DB

`V5__daily_log_routine.sql`: `daily_logs.walk_count`, `poop_count` smallint nullable, CHECK 0~20. 기존 행은 null.
