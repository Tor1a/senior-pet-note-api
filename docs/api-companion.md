# 함께한 날 · 기록 N일째(첫 기록일 기준) API 계약서 (`companion`)

- 작성: 2026-10-14 / 기획: 초보 견주 온보딩 E단계 (대표님 승인: `api-today.md` 링크 한 줄)
- 2026-10-15 변경(대표님 결정): `recordingDays` 를 등록일이 아니라 **첫 기록일** 기준으로 바꾸고, 기록이 아직 없으면 **null**. 필드는 그대로 두고 nullable 만 됐다(스키마 변경 없음).
- 마이그레이션 없음. `GET /api/pets/{petId}/today` 응답에 최상위 필드 `companion` 하나가 추가될 뿐이다(`api-today.md` 기존 문장·필드는 불변, 구버전 클라이언트는 무시하면 된다).

## 1. 응답

```json
"companion": { "togetherDays": 123, "recordingDays": 45 }
```

기록이 아직 없으면 `"companion": { "togetherDays": 123, "recordingDays": null }`.

- `pet.profileType == "first_time"` 일 때만 값을 채운다. 그 외(`senior`, 미선택 null, 기존 사용자)는 `"companion": null` 이다. 필드는 항상 존재한다.
- `togetherDays` = `recordDate - adoptedOn + 1`(입양일 당일 = 1일째). `adoptedOn` 이 없거나 `recordDate` 보다 미래이면 **null**. 0 이나 음수는 내려가지 않는다.
- `recordingDays` = `recordDate - 첫 기록일 + 1`(첫 기록일 당일 = 1일째), 최소 1. **첫 기록일** = 그 반려동물의 가장 이른 일일 기록(`daily_logs.record_date`, 오늘 화면 환영 카드가 보는 일일 기록과 같은 개념; 투약 체크는 세지 않는다)의 기록 날짜(새벽 4시 규칙으로 저장된 값). 기록한 횟수·연속일이 아니라 **첫 기록일 이후 경과일**이다. 안 적은 날이 있어도 줄지 않는다. 등록일은 쓰지 않는다.
- 일일 기록이 하나도 없으면 `recordingDays` 는 **null** 이다. 기록을 삭제(저장 되돌리기)해 마지막 기록이 사라지면 다시 null, 더 이른 기록이 남아 있으면 그 날짜 기준이 유지된다. 기록 줄을 그릴지는 클라이언트 몫이다(null 이면 그리지 않음, 환영 카드가 보이는 동안도 그리지 않음).
- `recordDate` 는 같은 응답의 `recordDate`(새벽 4시 규칙)다. 00:00~03:59 에는 전날 기준 숫자이고, 숫자는 달력 자정이 아니라 04:00 에 바뀐다. 첫 기록일은 저장된 기록 날짜를 그대로 쓴다(`app.zone` 기준).
- 입양일은 달력 날짜로 검증되므로(`api-pet-profile.md`) 03:59 에 달력 오늘 입양일이 저장돼 있으면 `recordDate` 보다 미래라 `togetherDays` 는 null 이다.
- 입양일을 수정하면 숫자가 바뀐다(저장하는 값은 없고 계산만 한다).

## 2. 기타

- 클라이언트는 날짜 차이를 계산하지 않는다(서버 값만 표시).
- 오류 코드 신규 없음. 본인 pet 만 조회(남의 pet 은 기존대로 404). 새 쿼리: first_time 일 때만 `min(record_date)` 1회(`DailyLogRepository.findFirstRecordDate`, userId·petId 조건).
- 값은 로그·이벤트·푸시에 기록하지 않는다. 신규 수집 항목 없음.
